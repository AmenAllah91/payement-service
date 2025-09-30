package com.example.payment_microservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Env {

    @Value("${app.realm:'empire'}")
    private  String REALM;

    @Value("${app.client-id:'coach-app'}")
    private  String CLIENT_ID;

    @Value("${app.client-secret:''}")
    private  String CLIENT_SECRET;

    @Value("${app.kc-host:'http://localhost:9081'}")
    private  String KEYCLOAK_HOST;

    public String getREALM() {
        return REALM;
    }

    public void setREALM(String REALM) {
        this.REALM = REALM;
    }

    public String getCLIENT_ID() {
        return CLIENT_ID;
    }

    public void setCLIENT_ID(String CLIENT_ID) {
        this.CLIENT_ID = CLIENT_ID;
    }

    public String getCLIENT_SECRET() {
        return CLIENT_SECRET;
    }

    public void setCLIENT_SECRET(String CLIENT_SECRET) {
        this.CLIENT_SECRET = CLIENT_SECRET;
    }

    public String getKEYCLOAK_HOST() {
        return KEYCLOAK_HOST;
    }

    public void setKEYCLOAK_HOST(String KEYCLOAK_HOST) {
        this.KEYCLOAK_HOST = KEYCLOAK_HOST;
    }
}
