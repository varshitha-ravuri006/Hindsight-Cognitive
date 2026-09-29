package com.vishwas.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Friendly page URLs for the static frontend: /health shows Hindsight, Groq and database status. */
@Controller
public class PageController {

    @GetMapping("/health")
    public String health() {
        return "forward:/health.html";
    }
}
