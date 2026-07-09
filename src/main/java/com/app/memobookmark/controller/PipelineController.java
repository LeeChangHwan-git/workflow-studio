package com.app.memobookmark.controller;

import com.app.memobookmark.model.Pipeline;
import com.app.memobookmark.service.PipelineService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("pipelines", pipelineService.findAll());
        return "pipeline/list";
    }

    @GetMapping("/new")
    public String newEditor(Model model) {
        model.addAttribute("pipeline", new Pipeline());
        model.addAttribute("isNew", true);
        return "pipeline/editor";
    }

    @GetMapping("/{id}")
    public String editor(@PathVariable String id, Model model) {
        Pipeline pipeline = pipelineService.findById(id);
        if (pipeline == null) return "redirect:/pipelines";
        model.addAttribute("pipeline", pipeline);
        model.addAttribute("isNew", false);
        return "pipeline/editor";
    }
}
