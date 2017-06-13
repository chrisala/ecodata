package au.org.ala.ecodata

import grails.converters.JSON


class JSONPInterceptor {

    JSONPInterceptor() {
        match(uri: '/ws/**')
    }

    boolean before() { true }

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
    }
}
