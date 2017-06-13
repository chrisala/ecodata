package au.org.ala.ecodata

import grails.converters.JSON
import grails.core.GrailsApplication
import org.springframework.web.context.request.RequestContextHolder

class AuditInterceptor {

    GrailsApplication grailsApplication
    UserService userService

    AuditInterceptor() {
        matchAll()
    }

    boolean before() {
        // userId is set from either the request param userId or failing that it tries to get it from
        // the UserPrincipal (assumes ecodata is being accessed directly via admin page)
        def userId = request.getHeader(grailsApplication.config.app.http.header.userId)?:RequestContextHolder.currentRequestAttributes()?.getUserPrincipal()?.attributes?.userid
        def userDetails = userService.setCurrentUser(userId)
        if (userDetails) {
            // We set the current user details in the request scope because
            // the 'afterView' hook can be called prior to the actual rendering (despite the name)
            // and the thread local can get clobbered before it is actually required.
            // Consumers who have access to the request can simply extract current user details
            // from there rather than use the service.
            request.setAttribute(UserDetails.REQUEST_USER_DETAILS_KEY, userDetails)
        }
        true
    }

    boolean after() {

        def ct = response.getContentType()
        if(ct?.contains("application/json") && model){
            String resp = model as JSON
            if(params.callback) {
                resp = params.callback + "(" + resp + ")"
            }
            render (contentType: "application/json", text: resp)
            false
        }
    }

    void afterView() {
        userService.clearCurrentUser()
    }
}
