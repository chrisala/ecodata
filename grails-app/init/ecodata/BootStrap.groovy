import au.org.ala.ecodata.AccessLevel
import au.org.ala.ecodata.AuditEventType
import au.org.ala.ecodata.GormEventListener
import grails.converters.JSON
import org.bson.BSON
import org.bson.Transformer
import org.bson.types.ObjectId
import org.grails.datastore.mapping.core.Datastore
import org.grails.web.json.JSONObject

import javax.imageio.ImageIO

class BootStrap {

    def elasticSearchService
    def grailsApplication
    def auditService

    def init = { servletContext ->
        // Add custom GORM event listener for ES indexing
        def ctx = servletContext.getAttribute(ApplicationAttributes.APPLICATION_CONTEXT)
        ctx.getBeansOfType(Datastore).values().each { Datastore d ->
            log.info "Adding listener for datastore: ${d}"
            ctx.addApplicationListener new GormEventListener(d, elasticSearchService, auditService)
        }

        // Index all docs
        //elasticSearchService.initialize()
        if (grailsApplication.config.app.elasticsearch.indexAllOnStartup) {
            elasticSearchService.indexAll()
        }

        // Allow groovy JSONObject$NULL to be saved (as null) to mongodb
        BSON.addEncodingHook(JSONObject.NULL.class, new Transformer() {
            public Object transform(Object o) {
                return null;
            }
        });

        // Allow GStrings to be saved to mongodb
        BSON.addEncodingHook(GString.class, new Transformer() {
            @Override
            Object transform(Object o) {
                return o?o.toString():null
            }
        })

        /**
         * Custom JSON serializer for {@link AccessLevel} enum
         */
        JSON.registerObjectMarshaller( AccessLevel ) { AccessLevel al ->
            return [
                    class: al.getClass().canonicalName,
                    name : al.name(),
                    code : al.getCode()
            ]
        }

        JSON.registerObjectMarshaller(ObjectId) { ObjectId objId ->
            return objId.toString()
        }

        JSON.registerObjectMarshaller(AuditEventType) { AuditEventType eventType ->
            return eventType.toString()
        }

        JSON.registerObjectMarshaller(JSONNull, {return ""})

        ImageIO.scanForPlugins()
    }

    def destroy = {
        // shutdown ES server
        //elasticSearchService.destroy()
    }
}
