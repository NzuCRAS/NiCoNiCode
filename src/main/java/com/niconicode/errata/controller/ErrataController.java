package com.niconicode.errata.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niconicode.common.result.R;
import com.niconicode.common.util.JwtUtil;
import com.niconicode.errata.dto.ErrataReq;
import com.niconicode.errata.dto.ErrataResp;
import com.niconicode.errata.entity.Errata;
import com.niconicode.errata.service.ErrataService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/errata")
@RequiredArgsConstructor
public class ErrataController {

    private final ErrataService errataService;
    private final JwtUtil jwtUtil;

    @PostMapping
    public R<Errata> submit(@Valid @RequestBody ErrataReq req, HttpServletRequest request) {
        Long userId = jwtUtil.getUserIdFromRequest(request);
        return R.ok(errataService.submit(userId, req));
    }

    @GetMapping("/doc")
    public R<List<ErrataResp>> listByDoc(
            @RequestParam String docType,
            @RequestParam Long docId) {
        return R.ok(errataService.listByDoc(docType, docId));
    }

    @GetMapping
    public R<Page<ErrataResp>> listAll(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {
        return R.ok(errataService.listAll(page, size, status));
    }

    @PutMapping("/{id}")
    public R<Errata> review(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        String adminNote = body.get("adminNote");
        return R.ok(errataService.review(id, status, adminNote));
    }
}
