package au.org.ala.ecodata

import au.org.ala.ecodata.reporting.OrganisationXlsExporter
import au.org.ala.ecodata.reporting.ProjectXlsExporter
import au.org.ala.ecodata.reporting.SummaryXlsExporter
import au.org.ala.ecodata.reporting.XlsExporter
import grails.converters.JSON
import groovy.json.JsonSlurper
import groovyx.net.http.ContentType
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsParameterMap
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.search.SearchHit

import static au.org.ala.ecodata.ElasticIndex.*
import java.text.SimpleDateFormat

class SearchController {

    static final String PUBLISHED_ACTIVITIES_FILTER = 'publicationStatus:published'

    SearchService searchService
    ElasticSearchService elasticSearchService
    ReportService reportService
    ProjectService projectService
    MetadataService metadataService
    DocumentService documentService
    ActivityService activityService
    SiteService siteService
    UserService userService
    DownloadService downloadService
    PermissionService permissionService
    SensitiveSpeciesService sensitiveSpeciesService
    ReportingService reportingService
    OrganisationService organisationService

    def index(String query) {
        def list = searchService.findForQuery(query, params)
        render list as JSON
    }

    def elastic() {
        if (params.terms) {
            params.terms = JSON.parse( params.terms)
        }

        def res = elasticSearchService.search(params.query, params, DEFAULT_INDEX)
        response.setContentType("application/json; charset=\"UTF-8\"")
        render res
    }

    def elasticHome() {
        Map geoSearch = null
        if (params.geoSearchJSON) {
            geoSearch = new JsonSlurper().parseText(params.geoSearchJSON)
        }
        def res = elasticSearchService.search(params.query, params, HOMEPAGE_INDEX, geoSearch)
        response.setContentType("application/json; charset=\"UTF-8\"")
        render res
    }

    /*
    * Searches the given query in project activity context.
    * Requires API key to prevent unauthorized access to embargoed records.
    */
    @RequireApiKey
    def elasticProjectActivity(){
        def res
        if (params?.version) {
            //search auditMessage
            res = (auditMessageSearch(params) as JSON).toString()
        } else {
            elasticSearchService.buildProjectActivityQuery(params)
            res = elasticSearchService.search(params.query, params, PROJECT_ACTIVITY_INDEX)
        }
        response.setContentType("application/json; charset=\"UTF-8\"")
        render res
    }

    /*
    * AuditMessage search that is equivalent to the elastic search with a version
    *
    *       elasticSearchService.buildProjectActivityQuery(params)
    *       res = elasticSearchService.search(params.query, params, PROJECT_ACTIVITY_INDEX)
    *
     */
    private def auditMessageSearch(params) {
        String userId = params.userId
        String projectId = params.projectId
        List<String> projectsTheUserIsAMemberOf

        //find project activities
        def all
        if (projectId) {
            all = AuditMessage.findAllByProjectIdAndEntityTypeAndDateLessThanEquals(projectId, ProjectActivity.class.name, new Date(params.version as Long), [sort:'date', order:'desc'])
        } else {
            all = AuditMessage.findAllByEntityTypeAndDateLessThanEquals(ProjectActivity.class.name, new Date(params.version as Long), [sort:'date', order:'desc'])
        }
        def projectActivities = []
        def found = []
        all.each {
            if (!found.contains(it.entityId)) {
                found << it.entityId

                if (it.eventType == AuditEventType.Update || it.eventType == AuditEventType.Insert) {
                    def added = false

                    it.entity.lastUpdated = it.date

                    switch (params.view) {

                        case 'myrecords':
                            if (userId && it.userId == userId) {
                                projectActivities << elasticSearchService.prepareActivityForIndexing(it.entity, params?.version)
                                added = true
                            }
                            break

                        case 'project':
                            if (projectId) {
                                if (userId && permissionService.isUserAlaAdmin(userId) || permissionService.isUserAdminForProject(userId, projectId) || permissionService.isUserEditorForProject(userId, projectId)) {
                                    projectActivities << elasticSearchService.prepareActivityForIndexing(it.entity, params?.version)
                                    added = true
                                } else if (userId && (!it.entity.embargoed || it.userId == userId)) {
                                    projectActivities << elasticSearchService.prepareActivityForIndexing(it.entity, params?.version)
                                    added = true
                                } else if (!userId && !it.entity.embargoed) {
                                    projectActivities << elasticSearchService.prepareActivityForIndexing(it.entity, params?.version)
                                    added = true
                                }
                            }
                            break

                        case 'allrecords':
                            if (!projectId) {
                                if (userId && permissionService.isUserAlaAdmin(userId)) {
                                    projectActivities << elasticSearchService.prepareActivityForIndexing(it.entity, params?.version)
                                    added = true
                                } else if (userId) {
                                    if (!projectsTheUserIsAMemberOf) projectsTheUserIsAMemberOf = permissionService.getProjectsForUser(userId, AccessLevel.admin, AccessLevel.editor)

                                    if ((!projectsTheUserIsAMemberOf || projectsTheUserIsAMemberOf.contains(it.projectId)) &&
                                            (!it.entity.embargoed || it.userId == userId)) {
                                        projectActivities << elasticSearchService.prepareActivityForIndexing(it.entity, params?.version)
                                        added = true
                                    }
                                } else if (!userId && !it.entity.embargoed) {
                                    projectActivities << elasticSearchService.prepareActivityForIndexing(it.entity, params?.version)
                                    added = true
                                }
                            }
                            break
                    }

                    if (!added) {
                        if (!it.entity.embargoed) {
                            projectActivities << it.entity
                        }
                    }
                }
            }
        }

        [hits: [hits: projectActivities.collect { [_source: it]}, total: projectActivities.size() ]]
    }

    private def populateGeoInfo(markBy, hit, selectedFacetTerms){

        def geo = hit.source.geo
        if(!markBy) {
            geo[0].geometry = hit.source.sites[0].extent.geometry
            return geo
        }

        def legendName, index
        // When fields are indexed, "Facet" or "Name" is appended to the field name.
        String propertyName = markBy.replaceAll("Facet", "")

        def facetValue = hit.source[propertyName] ?:""

        if (facetValue) {
            // Geographic facets will be List typed (as a site can be in more than one state for example)
            // We have to assign the site to a category, so we'll just pick the first one.
            if (facetValue instanceof List) {
                facetValue = facetValue[0]
            }
            for(int i = 0; i < selectedFacetTerms.size(); i++){
                if(selectedFacetTerms[i].legendName.equals(facetValue)){
                    legendName = selectedFacetTerms[i].legendName
                    index = selectedFacetTerms[i].index
                    selectedFacetTerms[i].count++
                    break;
                }
            }

            geo.each{ data ->
                data.legendName = legendName
                data.index = index
            }
        }
        else {
            hit.source.sites.each { site ->
                if(site.extent?.geometry) {
                    facetValue =  site.extent?.geometry[propertyName] ?: ""

                    if(facetValue) {
                        // Geographic facets will be List typed (as a site can be in more than one state for example)
                        // We have to assign the site to a category, so we'll just pick the first one.
                        if (facetValue instanceof List) {
                            facetValue = facetValue[0]
                        }
                        for(int i = 0; i < selectedFacetTerms.size(); i++){
                            if(selectedFacetTerms[i].legendName.equals(facetValue)){
                                legendName = selectedFacetTerms[i].legendName
                                index = selectedFacetTerms[i].index
                                selectedFacetTerms[i].count++
                                break;
                            }
                        }

                        geo.each{ data ->
                            if(data.siteId.equals(site.siteId)) {
                                data.legendName = legendName
                                data.index = index
                            }
                        }
                    }
                }
            }
        }

        geo
    }

    def elasticGeo() {
        Map geoSearch = null
        if (params.geoSearchJSON) {
            geoSearch = new JsonSlurper().parseText(params.geoSearchJSON)
        }

        def res = elasticSearchService.search(params.query, params, "homepage", geoSearch)
        def selectedFacetTerms = []
        def markBy = params.markBy

        if(markBy){
            res.facets.facets.each{ facet ->
                if(facet.key.equals(markBy)){
                    facet.value.eachWithIndex{ val, index ->
                        def data = [:]
                        data.legendName = val.term.toString()
                        data.index = index
                        data.count = 0
                        selectedFacetTerms << data
                    }
                }
            }
        }

        def geoRes = []

        res.hits.hits.each { hit ->
            if(hit.source?.geo) {
                def proj = [:]
                proj.projectId = hit.source.projectId
                proj.name = hit.source.name
                proj.org = hit.source.organisationName
                proj.geo = populateGeoInfo(markBy, hit, selectedFacetTerms)

                geoRes << proj
            }
        }
        response.setContentType("application/json; charset=\"UTF-8\"")
        def projectsAndTotal = ['total':res.hits.getTotalHits(),'projects':geoRes,'selectedFacetTerms':selectedFacetTerms]

        render projectsAndTotal as JSON
    }
    def elasticPost() {
        def paramsObj = request.JSON
        def paramMap = new GrailsParameterMap(paramsObj, request)
        log.debug "paramMap = ${paramMap}"

        if (paramMap) {
            def res = elasticSearchService.search(paramMap.query, paramMap, "")
            response.setContentType("application/json; charset=\"UTF-8\"")
            render res
        } else {
            def msg = [error: "Required JSON body not found"]
            render msg as JSON
        }
    }

    def clearIndex() {
        log.debug "Clearing index"
        render elasticSearchService.deleteIndex()
    }

    def indexAll() {
        render elasticSearchService.indexAll() as JSON
    }

    def dashboardReport() {

        def filters = params.getList("fq")
        List<Score> scores = Score.findAll()
        def results = reportService.aggregate(filters, params.query ?: "*:*", scores)
        render results as JSON
    }

    def scoresByLabel() {
        def scores = params.getList("scores")

        def filters = params.getList("fq")
        def searchTerm = params.query ?: "*:*"

        def results = reportService.aggregate(filters, searchTerm, reportService.findScoresByLabel(scores))
        render results as JSON
    }

    def targetsReportByScoreLabel() {
        def scoreLabels = params.getList("scores")
        def scores = reportService.findScoresByLabel(scoreLabels)
        def filters = params.getList("fq")
        def searchTerm = params.query ?: "*:*"
        def targets = reportService.outputTargetsBySubProgram(params, scores)
        def scoresReport = reportService.outputTargetReport(filters, searchTerm, scores)

        def results = [scores:scoresReport, targets:targets]
        render results as JSON
    }

    def targetsReport() {
        def filters = params.getList("fq")
        def searchTerm = params.query ?: "*:*"

        def targets = reportService.outputTargetsBySubProgram(params)
        def scores = reportService.outputTargetReport(filters, searchTerm)

        def results = [scores:scores, targets:targets]
        render results as JSON
    }

    @RequireApiKey
    def activityReport() {
        Map params = request.JSON
        def results = reportService.runActivityReport(params.query ?: "*:*", params.fq, params.reportConfig, params.approvedActivitiesOnly?:true)
        render results as JSON
    }

    def downloadProjectDataFile() {
        if (!params.id) {
            response.setStatus(400)
            render "A download ID is required"
        } else {
            String extension = params.fileExtension ?: 'zip'
            File file = new File("${grailsApplication.config.temp.dir}${File.separator}${params.id}.${extension}")
            if (file) {
                response.setContentType(ContentType.BINARY.toString())
                response.setHeader('Content-Disposition', 'Attachment;Filename="data.'+extension+'"')

                file.withInputStream { i -> response.outputStream << i }
            } else {
                response.setStatus(404)
                render "No download was found for id ${params.id}"
            }
        }
    }

    @RequireApiKey
    def downloadAllData() {
        if (params.containsKey("isMerit") && !params.isMerit.toBoolean()) {
            params.max = 10000
            params.offset = 0

            if (params.async?.toBoolean()) {
                if (!params.email) {
                    response.setStatus(400)
                    render "An email address must be provided for asynchronous downloads"
                } else {
                    downloadService.downloadProjectDataAsync(params)

                    response.setStatus(200)
                    render "OK"
                }
            } else {
                response.setContentType(ContentType.BINARY.toString())
                response.setHeader('Content-Disposition', 'Attachment;Filename="data.zip"')

                downloadService.downloadProjectData(response.outputStream, params)
            }
        } else {
            downloadMeritData(params)
            response.setStatus(200)
            render "OK"
        }
    }

    void downloadMeritData(GrailsParameterMap params) {
        if (!params.max) {
            params.max = 5000
            params.offset = 0
        }

        Set ids = downloadService.getProjectIdsForDownload(params, HOMEPAGE_INDEX)

        withFormat {
            json {
                List projects = ids.collect { projectService.get(it, ProjectService.ALL) }
                render projects as JSON
            }
            xlsx {
                if (!params.email) {
                    params.email = userService.getCurrentUserDetails().userName
                }
                params.fileExtension = "xlsx"
                Closure doDownload = { OutputStream outputStream, GrailsParameterMap paramMap ->
                    XlsExporter exporter = exportMeritProjectsToXls(ids, params.getList('tabs'))
                    exporter.save(outputStream)
                }
                downloadService.downloadProjectDataAsync(params, doDownload)
            }
        }
    }

    private XlsExporter exportMeritProjectsToXls(Set<String> projectIds, List<String> tabsToExport) {
        long start = System.currentTimeMillis()

        File file = File.createTempFile("download", "xlsx")
        XlsExporter xlsExporter = new XlsExporter(file.name)

        ProjectXlsExporter projectExporter = new ProjectXlsExporter(projectService, xlsExporter, tabsToExport)

        Project.withSession { session ->
            int batchSize = 50
            List projects = new ArrayList(batchSize)
            for (int i = 0; i < projectIds.size(); i++) {
                projects << projectService.get(projectIds[i], ProjectService.ALL)

                if (i % batchSize == batchSize - 1 || i == projectIds.size() - 1) {
                    projectExporter.exportAllProjects(projects)
                    projects.clear()
                    session.clear()

                    log.info "Exported ${i + 1} of ${projectIds.size()} projects..."
                }
            }
        }
        log.info "Exporting ${projectIds.size()} projects took ${System.currentTimeMillis() - start} millis"

        xlsExporter
    }

    def downloadOrganisationData() {
        if (!params.email) {
            params.email = userService.getCurrentUserDetails().userName
        }
        params.max = 10000
        params.offset = 0
        params.fileExtension = "xlsx"

        Collection<String> orgIds = downloadService.getProjectIdsForDownload(params, DEFAULT_INDEX, 'organisationId')
        Closure doDownload = { OutputStream outputStream, GrailsParameterMap paramMap ->

            XlsExporter exporter = exportOrganisationsToXls(orgIds, paramMap.getList('tabs'))
            exporter.save(outputStream)
        }
        downloadService.downloadProjectDataAsync(params, doDownload)

        response.status = 200
        render "OK"
    }

    private XlsExporter exportOrganisationsToXls(Collection<String> organisationIds, List<String> tabs) {
        File file = File.createTempFile("download", "xlsx")
        XlsExporter xlsExporter = new XlsExporter(file.name)

        OrganisationXlsExporter exporter = new OrganisationXlsExporter(xlsExporter, tabs, [:])

        Organisation.withSession { session ->
            int batchSize = 50

            for (int i = 0; i < organisationIds.size(); i++) {
                String organisationId = organisationIds[i]
                Map organisation = organisationService.get(organisationId)
                if (organisation) {
                    organisation.reports = reportingService.findAllByOwner('organisationId', organisationId)


                    exporter.export(organisation)
                    if (i % batchSize == 1) {
                        session.clear()
                    }

                    log.info "Exported ${i + 1} of ${organisationIds.size()} organisations..."
                }
            }
        }
        xlsExporter
    }


    @RequireApiKey
    def downloadSummaryData() {

        def defaultCategory = "Not categorized"
        def filters = params.getList("fq")

        def results = reportService.aggregate(filters)
        def scores = results.outputData
        def scoresByCategory = scores.groupBy{
            (it.score.category?:defaultCategory)
        }

        withFormat {
            json {
                render scoresByCategory as JSON
            }
            xlsx {
                XlsExporter exporter = new XlsExporter("results")
                exporter.setResponseHeaders(response)

                SummaryXlsExporter summaryXlsExporter = new SummaryXlsExporter(exporter)
                summaryXlsExporter.exportAll(scoresByCategory)
                exporter.sizeColumns()

                exporter.save(response.outputStream)
            }
        }
    }

    @RequireApiKey
    def downloadUserList() {

        if (!params.email) {
            params.email = userService.getCurrentUserDetails().userName
        }

        params.fileExtension = "csv"

        Map searchParams = [fq:params.fq, query:params.query?:"*:*", max:10000, offset:0]

        Closure doDownload = { OutputStream outputStream, GrailsParameterMap paramMap ->

            try {
                Set projectIds = downloadService.getProjectIdsForDownload(searchParams, HOMEPAGE_INDEX)

                List meritRoles = ['ROLE_FC_READ_ONLY', 'ROLE_FC_OFFICER', 'ROLE_FC_ADMIN']
                Map users = reportService.userSummary(projectIds, meritRoles)

                outputStream.withWriter { writer ->
                    writer.println("User Id, Name, Email, Role, Project ID, Grant ID, External ID, Project Name, Project Access Role")

                    users.values().each { user->

                        writer.print(user.userId+","+user.name+","+user.email+","+user.role+",")
                        if (user.projects) {
                            boolean first = true
                            user.projects.each { project ->
                                if (!first) {
                                    writer.print(",,,,")
                                }
                                writer.println(project.projectId+","+project.grantId+","+project.externalId+",\""+project.name+"\","+project.access)
                                first = false
                            }
                        }
                        else {
                            writer.println()
                        }


                    }
                }
            }
            catch (Exception e) {
                e.printStackTrace()
            }
        }
        downloadService.downloadProjectDataAsync(params, doDownload)

        response.status = 200
        render "OK"

    }

    @RequireApiKey
    def downloadShapefile() {

        if (!params.max) {
            params.max = 1000
            params.offset = 0
        }
        if (!params.email) {
            params.email = userService.getCurrentUserDetails().userName
        }
        params.fileExtension = "zip"
        def query = params.query
        if (!query) {
            query = '*'
        }

        SearchResponse res = elasticSearchService.search(query, params, "homepage")

        Set ids = new HashSet()

        for (SearchHit hit : res.hits.hits) {
            if (hit.source.projectId) {
                ids << hit.source.projectId
            }
        }

        Closure doDownload = {  OutputStream outputStream, GrailsParameterMap paramMap ->
            SimpleDateFormat format = new SimpleDateFormat('yyyy-MM-dd')
            def name = 'meritSites-' + format.format(new Date())

            reportService.exportShapeFile(ids, name, outputStream)
        }
        downloadService.downloadProjectDataAsync(params, doDownload)
        response.status = 200
        render "OK"
    }

    /**
     * Check given species is in a senstive list.
     * @param name species common name or scientific name
     * @param lat latitude
     * @param lng latitude
     * @return generalised lat and lng value
     */
    def sensitiveSpecies(String name, double lat, double lng){
        if(name && lat && lng){
            def result = sensitiveSpeciesService.findSpecies(name, lat, lng)
            if(result) {
                render ([status:'ok', text:"sensitive species", result: result] as JSON)
            } else {
                render ([status:'ok', text:"not a sensitive species"] as JSON)
            }
        } else {
            response.setStatus(400)
            render ([status:'error', error:'Invalid query (expected: name, lat and lng)'] as JSON)
        }
    }
}