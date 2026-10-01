package com.app.workflowstudio.controller;

import com.app.workflowstudio.model.Memo;
import com.app.workflowstudio.service.MemoService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/memos")
public class MemoController {

    private final MemoService memoService;

    public MemoController(MemoService memoService) {
        this.memoService = memoService;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String category,
                       @RequestParam(required = false) String search,
                       Model model) {
        List<Memo> memos;
        if (search != null && !search.isBlank()) {
            memos = memoService.search(search);
            model.addAttribute("search", search);
        } else if (category != null && !category.isBlank()) {
            memos = memoService.findByCategory(category);
            model.addAttribute("category", category);
        } else {
            memos = memoService.findAll();
        }
        model.addAttribute("memos", memos);
        return "memo/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("memo", new Memo());
        return "memo/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable String id, Model model) {
        Memo memo = memoService.findById(id);
        if (memo == null) return "redirect:/memos";
        model.addAttribute("memo", memo);
        return "memo/form";
    }

    @PostMapping
    public String create(@ModelAttribute Memo memo) {
        memoService.create(memo);
        return "redirect:/memos";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @ModelAttribute Memo memo) {
        memoService.update(id, memo);
        return "redirect:/memos";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id) {
        memoService.delete(id);
        return "redirect:/memos";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model) {
        Memo memo = memoService.findById(id);
        if (memo == null) return "redirect:/memos";
        model.addAttribute("memo", memo);
        return "memo/detail";
    }
}
