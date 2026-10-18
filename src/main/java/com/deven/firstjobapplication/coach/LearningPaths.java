package com.deven.firstjobapplication.coach;

import com.deven.firstjobapplication.coach.Models.*;

import java.util.List;
import java.util.Map;

/** Hand-picked official resources, a time estimate and a small proof project for every skill in the taxonomy. */
public final class LearningPaths {
    private LearningPaths() { }

    private static LearningResource docs(String title, String url) { return new LearningResource(title, url, "Docs"); }
    private static LearningResource guide(String title, String url) { return new LearningResource(title, url, "Guide"); }
    private static LearningResource practice(String title, String url) { return new LearningResource(title, url, "Practice"); }

    private static final Map<Skill, LearningPath> PATHS = Map.of(
            Skill.JAVA, new LearningPath(Skill.JAVA, "Java", "about 6 hours",
                    List.of(guide("Dev.java: Learn Java", "https://dev.java/learn/"), docs("The Java Tutorials (Oracle)", "https://docs.oracle.com/javase/tutorial/")),
                    "Write a small command-line tracker (for example a job-application log) with classes, collections and a few unit tests.",
                    List.of("Model your data with a class or record", "Store items in a List or Map and sort or filter them", "Handle bad input with exceptions", "Commit it with a README that shows how to run it")),
            Skill.SPRING_BOOT, new LearningPath(Skill.SPRING_BOOT, "Spring Boot", "about 4 hours",
                    List.of(guide("Getting Started: Building an Application with Spring Boot", "https://spring.io/guides/gs/spring-boot"), guide("Building a RESTful Web Service", "https://spring.io/guides/gs/rest-service")),
                    "Build a Spring Boot service with a controller, a service class and configuration in application.properties.",
                    List.of("Generate a project with Spring Initializr", "Add a @RestController and a @Service", "Move settings into application.properties", "Run it and call it with curl")),
            Skill.REST_API, new LearningPath(Skill.REST_API, "REST APIs", "about 3 hours",
                    List.of(docs("HTTP request methods (MDN)", "https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods"), guide("GitHub REST API: getting started", "https://docs.github.com/en/rest/guides/getting-started-with-the-rest-api")),
                    "Expose GET, POST, PUT and DELETE routes for one resource, validate the input and return correct status codes.",
                    List.of("Choose one resource and name its routes", "Return 400 for invalid input and 404 for unknown ids", "Document an example request and response in the README", "Call each route once and keep the examples")),
            Skill.SQL, new LearningPath(Skill.SQL, "SQL", "about 3 hours",
                    List.of(practice("SQLBolt: interactive lessons", "https://sqlbolt.com/"), docs("PostgreSQL tutorial: the SQL language", "https://www.postgresql.org/docs/current/tutorial-sql.html")),
                    "Add a queries/ folder with a SELECT that JOINs two tables, a GROUP BY report and an index, plus a note on what each one answers.",
                    List.of("Create two related tables in schema.sql", "Write a SELECT ... JOIN across them", "Add a GROUP BY query with a count", "Commit the queries next to the schema")),
            Skill.POSTGRESQL, new LearningPath(Skill.POSTGRESQL, "PostgreSQL", "about 3 hours",
                    List.of(docs("PostgreSQL tutorial", "https://www.postgresql.org/docs/current/tutorial.html"), guide("Accessing Relational Data using JDBC with Spring", "https://spring.io/guides/gs/relational-data-access")),
                    "Run PostgreSQL in Docker Compose and make your app read and write a real table through its connection settings.",
                    List.of("Start PostgreSQL with docker compose", "Put the JDBC URL in configuration, not code", "Create the table and save one row from the app", "Describe the setup in the README")),
            Skill.REACT, new LearningPath(Skill.REACT, "React", "about 5 hours",
                    List.of(guide("React: Learn", "https://react.dev/learn"), guide("Tutorial: Tic-Tac-Toe", "https://react.dev/learn/tutorial-tic-tac-toe")),
                    "Build a small React page with components, state and a form that calls your own API.",
                    List.of("Split the page into two or three components", "Keep form input in useState", "Fetch data in useEffect and show loading and error states", "Deploy it or add a screenshot to the README")),
            Skill.DOCKER, new LearningPath(Skill.DOCKER, "Docker", "about 2 hours",
                    List.of(guide("Docker: Get started", "https://docs.docker.com/get-started/"), docs("Building best practices", "https://docs.docker.com/build/building/best-practices/")),
                    "Write a multi-stage Dockerfile for your app and a docker-compose.yml that also starts its database.",
                    List.of("Write a Dockerfile that builds and runs the app", "Use a small runtime image and a non-root user", "Add docker-compose.yml with the database", "Add the run commands to the README")),
            Skill.TESTING, new LearningPath(Skill.TESTING, "Testing", "about 2 hours",
                    List.of(docs("JUnit 5 user guide", "https://junit.org/junit5/docs/current/user-guide/"), guide("Testing the Web Layer (Spring)", "https://spring.io/guides/gs/testing-web")),
                    "Write one unit test for pure logic and one test for an API route, including a failing-input case.",
                    List.of("Pick the function with the most branching", "Test the normal case and one bad input", "Test one HTTP route end to end", "Note the test command in the README")),
            Skill.GIT, new LearningPath(Skill.GIT, "Git", "about 2 hours",
                    List.of(docs("Pro Git (book)", "https://git-scm.com/book/en/v2"), guide("GitHub flow", "https://docs.github.com/en/get-started/using-github/github-flow")),
                    "Work one feature through a branch, a pull request and a merge, with clear commit messages and a .gitignore.",
                    List.of("Create a branch for one small feature", "Commit in small steps with clear messages", "Open and merge a pull request", "Add a .gitignore so secrets and build output stay out")));

    public static LearningPath of(Skill skill) { return PATHS.get(skill); }
    public static List<LearningPath> all() { return java.util.Arrays.stream(Skill.values()).map(PATHS::get).toList(); }
}
