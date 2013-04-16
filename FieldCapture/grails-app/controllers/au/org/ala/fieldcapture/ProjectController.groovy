package au.org.ala.fieldcapture

import grails.converters.JSON

class ProjectController {

    def projectService, siteService
    static defaultAction = "index"

    def index(String id) {
        def project = projectService.get(id)
        if (!project || project.error) {
            forward(action: 'list', model: [error: project.error])
        } else {
            project.sites?.sort {it.name}
            //todo: ensure there are no control chars (\r\n etc) in the json as
            //todo:     this will break the client-side parser
            [project: project, json: (project.sites as JSON).toString(), mapFeatures: projectService.getMapFeatures(project)]
        }
    }

    def edit(String id) {
        def project = projectService.get(id)
        if (project) {
            [project: project]
        } else {
            forward(action: 'list', model: [error: 'no such id'])
        }
    }

    def update(String id) {
        //params.each { println it }
        projectService.update(id, (params as JSON).toString())
        chain action: 'index', id: id
    }

    def list() {
        // will show a list of projects
        // but for now just go home
        forward(controller: 'home')
    }
}