package com.abhinav.taskflow.common.security;

public class TaskflowClaims {

    public static final String SESSION_ID = "sid";
    public static final String USERNAME = "preferred_username"; // the standard OIDC claim name
    public static final String ROLE = "role";

    private TaskflowClaims() {}
}
