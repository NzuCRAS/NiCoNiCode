package com.niconicode.errata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niconicode.agent.tracker.entity.TechReport;
import com.niconicode.agent.tracker.mapper.TechReportMapper;
import com.niconicode.auth.entity.User;
import com.niconicode.auth.mapper.UserMapper;
import com.niconicode.common.exception.BusinessException;
import com.niconicode.errata.dto.ErrataReq;
import com.niconicode.errata.dto.ErrataResp;
import com.niconicode.errata.entity.Errata;
import com.niconicode.errata.mapper.ErrataMapper;
import com.niconicode.knowledge.entity.KnowledgeDoc;
import com.niconicode.knowledge.mapper.KnowledgeDocMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ErrataService {

    private final ErrataMapper errataMapper;
    private final UserMapper userMapper;
    private final TechReportMapper techReportMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;

    public Errata submit(Long userId, ErrataReq req) {
        Errata errata = new Errata();
        errata.setDocType(req.getDocType());
        errata.setDocId(req.getDocId());
        errata.setUserId(userId);
        errata.setContent(req.getContent());
        errata.setStatus("PENDING");
        errataMapper.insert(errata);
        return errata;
    }

    public List<ErrataResp> listByDoc(String docType, Long docId) {
        List<Errata> list = errataMapper.selectList(
                new LambdaQueryWrapper<Errata>()
                        .eq(Errata::getDocType, docType)
                        .eq(Errata::getDocId, docId)
                        .eq(Errata::getStatus, "ACCEPTED")  // 只返回已接纳的勘误
                        .orderByDesc(Errata::getCreatedAt));
        return list.stream().map(this::toResp).collect(Collectors.toList());
    }

    public Page<ErrataResp> listAll(int page, int size, String status) {
        LambdaQueryWrapper<Errata> wrapper = new LambdaQueryWrapper<Errata>()
                .eq(status != null && !status.isBlank(), Errata::getStatus, status)
                .orderByDesc(Errata::getCreatedAt);
        Page<Errata> raw = errataMapper.selectPage(new Page<>(page, size), wrapper);

        Page<ErrataResp> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(this::toResp).collect(Collectors.toList()));
        return result;
    }

    public Errata review(Long id, String status, String adminNote) {
        Errata errata = errataMapper.selectById(id);
        if (errata == null) throw new BusinessException(404, "勘误不存在");
        errata.setStatus(status);
        errata.setAdminNote(adminNote);
        errataMapper.updateById(errata);
        return errata;
    }

    private ErrataResp toResp(Errata errata) {
        ErrataResp resp = new ErrataResp();
        resp.setId(errata.getId());
        resp.setDocType(errata.getDocType());
        resp.setDocId(errata.getDocId());
        resp.setUserId(errata.getUserId());
        resp.setContent(errata.getContent());
        resp.setStatus(errata.getStatus());
        resp.setAdminNote(errata.getAdminNote());
        resp.setCreatedAt(errata.getCreatedAt());
        resp.setUpdatedAt(errata.getUpdatedAt());

        // 填充用户昵称
        User user = userMapper.selectById(errata.getUserId());
        resp.setUserNickname(user != null ? user.getNickname() : "未知用户");

        // 填充文档标题
        if ("TECH_REPORT".equals(errata.getDocType())) {
            TechReport report = techReportMapper.selectById(errata.getDocId());
            resp.setDocTitle(report != null ? report.getTitle() : "未知报道");
        } else {
            KnowledgeDoc doc = knowledgeDocMapper.selectById(errata.getDocId());
            resp.setDocTitle(doc != null ? doc.getTitle() : "未知文档");
        }

        return resp;
    }
}
