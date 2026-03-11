package com.niconicode.category.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.category.entity.Category;
import com.niconicode.category.mapper.CategoryMapper;
import com.niconicode.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper categoryMapper;

    public List<Category> listAll() {
        return categoryMapper.selectList(
                new LambdaQueryWrapper<Category>().orderByAsc(Category::getSortOrder));
    }

    public Category getById(Long id) {
        Category cat = categoryMapper.selectById(id);
        if (cat == null) throw new BusinessException(404, "分类不存在");
        return cat;
    }

    public Category create(Category category) {
        if (category.getSortOrder() == null) category.setSortOrder(0);
        categoryMapper.insert(category);
        return category;
    }

    public Category update(Long id, Category updated) {
        Category cat = categoryMapper.selectById(id);
        if (cat == null) throw new BusinessException(404, "分类不存在");
        if (updated.getName() != null) cat.setName(updated.getName());
        if (updated.getDescription() != null) cat.setDescription(updated.getDescription());
        if (updated.getSortOrder() != null) cat.setSortOrder(updated.getSortOrder());
        categoryMapper.updateById(cat);
        return cat;
    }

    public void delete(Long id) {
        categoryMapper.deleteById(id);
    }
}
