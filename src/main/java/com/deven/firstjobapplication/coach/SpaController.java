package com.deven.firstjobapplication.coach;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Public profile links (/u/login) are pages of the React app, so the server hands them the app shell. */
@Controller
public class SpaController {
    @GetMapping("/u/{login}") public String profile() { return "forward:/index.html"; }
    @GetMapping({"/reset", "/verify"}) public String accountLink() { return "forward:/index.html"; }
}
