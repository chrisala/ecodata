package au.org.ala.ecodata

class DocumentService {

    static final ACTIVE = "active"
    static final FILE_LOCK = new Object()

    def commonService, grailsApplication
    
    /**
     * Converts the domain object into a map of properties, including
     * dynamic properties.
     * @param document an Document instance
     * @param levelOfDetail list of features to include
     * @return map of properties
     */
    def toMap(document, levelOfDetail = []) {
        def dbo = document.getProperty("dbo")
        def mapOfProperties = dbo.toMap()
        def id = mapOfProperties["_id"].toString()
        mapOfProperties["id"] = id
        mapOfProperties.remove("_id")
        // construct document url based on the current configuration
        mapOfProperties.url = urlFor(mapOfProperties)
        mapOfProperties.findAll {k,v -> v != null}
    }

    def get(id, levelOfDetail = []) {
        def o = Document.findByDocumentIdAndStatus(id, ACTIVE)
        return o ? toMap(o, levelOfDetail) : null
    }

    def getAll(boolean includeDeleted = false, levelOfDetail = []) {
        includeDeleted ?
            Document.list().collect { toMap(it, levelOfDetail) } :
            Document.findAllByStatus(ACTIVE).collect { toMap(it, levelOfDetail) }
    }

    def findAllForProjectId(id, levelOfDetail = []) {
        Document.findAllByProjectIdAndStatus(id, ACTIVE).collect { toMap(it, levelOfDetail) }
    }

    def findAllForActivityId(id, levelOfDetail = []) {
        Document.findAllByActivityIdAndStatus(id, ACTIVE).collect { toMap(it, levelOfDetail) }
    }

    def findAllForOutputId(id, levelOfDetail = []) {
        Document.findAllByOutputIdAndStatus(id, ACTIVE).collect { toMap(it, levelOfDetail) }
    }

    /**
     * Creates a new Document object associated with the supplied file.
     * @param props the desired properties of the Document.
     * @param fileIn an InputStream attached to the file to save.  This will be saved to the uploads directory.
     */
    def create(props, fileIn) {
        def d = new Document(documentId: Identifiers.getNew(true,''))
        props.remove 'documentId'
        try {
            if (fileIn) {
                props.filename = saveFile(props.filename, fileIn, false)
            }
            commonService.updateProperties(d, props)
            return [status:'ok',documentId:d.documentId, url:urlFor(d)]
        } catch (Exception e) {
            // clear session to avoid exception when GORM tries to autoflush the changes
            e.printStackTrace()

            Document.withSession { session -> session.clear() }
            def error = "Error creating document for ${props.filename} - ${e.message}"
            log.error error
            return [status:'error',error:error]
        }
    }

    /**
     * Updates a new Document object and optionally it's attached file.
     * @param props the desired properties of the Document.
     * @param fileIn (optional) an InputStream attached to the file to save.  If supplied, this will overwrite any
     * file with the same name in the uploads directory.
     */
    def update(props, id, fileIn = null) {
        def d = Document.findByDocumentId(id)
        if (d) {
            try {
                if (fileIn) {
                    props.filename = saveFile(props.filename, fileIn, true)
                }
                commonService.updateProperties(d, props)
                return [status:'ok',documentId:d.documentId, url:urlFor(d)]
            } catch (Exception e) {
                Document.withSession { session -> session.clear() }
                def error = "Error updating document ${id} - ${e.message}"
                log.error error
                return [status:'error',error:error]
            }
        } else {
            def error = "Error updating document - no such id ${id}"
            log.error error
            return [status:'error',error:error]
        }
    }

    /**
     * Saves the contents of the supplied InputStream to the file system, using the supplied filename.  If overwrite
     * is false and a file with the supplied filename exists, a new filename will be generated by pre-pending the
     * filename with a counter.
     * @param filename the name to save the file.
     * @param fileIn an InputStream containing the contents of the file to save.
     * @param overwrite true if an existing file should be overwritten.
     * @return the filename (not the full path) the file was saved using.  This may not be the same as the supplied
     * filename in the case that overwrite is false.
     */
    private String saveFile(filename, fileIn, overwrite) {
        if (fileIn) {
            synchronized (FILE_LOCK) {
                if (!overwrite) {
                    filename = nextUniqueFileName(filename)
                }
                new FileOutputStream(fullPath(filename)).withStream { it << fileIn }
            }
        }
        return filename
    }

    /**
     * We are preserving the file name so the URLs look nicer and the file extension isn't lost.
     * As filename are not guaranteed to be unique, we are pre-pending the file with a counter if necessary to
     * make it unique.
     */
    private String nextUniqueFileName(filename) {
        int counter = 0;
        String newFilename = filename
        while (new File(fullPath(newFilename)).exists()) {
            newFilename = "${counter}_${filename}"
            counter++;
        };
        return newFilename;
    }

    String fullPath(filename) {
        return grailsApplication.config.app.file.upload.path + '/' +filename
    }

    /**
     * Returns a String containing the URL by which the file attached to the supplied document can be downloaded.
     */
    def urlFor(document) {
        if (!document.filename) {
            return ''
        }
        URI uri = new URI(grailsApplication.config.app.uploads.url + document.filename.replace(' ','%20'))
        return uri.toURL();
    }

    /**
     * Deletes the file associated with the supplied Document from the file system.
     * @param document identifies the file to delete.
     * @return true if the delete operation was successful.
     */
    def deleteFile(document) {

        File f = fullPath(document.filename)
        return f.delete();

    }


}
