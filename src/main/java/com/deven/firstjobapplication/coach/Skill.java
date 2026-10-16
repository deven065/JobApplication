package com.deven.firstjobapplication.coach;

/** Deliberately small, explainable taxonomy for the hackathon prototype. */
public enum Skill {
    JAVA("Java"), SPRING_BOOT("Spring Boot"), REST_API("REST APIs"), SQL("SQL"),
    POSTGRESQL("PostgreSQL"), REACT("React"), DOCKER("Docker"), TESTING("Testing"), GIT("Git");

    private final String label;
    Skill(String label) { this.label = label; }
    public String label() { return label; }
}
