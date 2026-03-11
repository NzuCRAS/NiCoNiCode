package com.niconicode.category.controller;

import com.niconicode.category.entity.Category;
import com.niconicode.category.service.CategoryService;
import com.niconicode.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public R<List<Category>> listAll() {
        return R.ok(categoryService.listAll());
    }

    @GetMapping("/{id}")
    public R<Category> getById(@PathVariable Long id) {
        return R.ok(categoryService.getById(id));
    }

    @PostMapping
    public R<Category> create(@RequestBody Category category) {
        return R.ok(categoryService.create(category));
    }

    @PutMapping("/{id}")
    public R<Category> update(@PathVariable Long id, @RequestBody Category category) {
        return R.ok(categoryService.update(id, category));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return R.ok();
    }
}
