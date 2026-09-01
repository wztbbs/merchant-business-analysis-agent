package com.merchant.analysis.api;

import com.merchant.analysis.conversation.ConversationRepository;
import com.merchant.analysis.conversation.SessionRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话只读接口：会话列表（侧边栏，用户显式选中历史会话后继续追问）+ 会话历史轮次。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationRepository conversationRepository;

    public ConversationController(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    /** 会话列表：该用户的会话，按最近活跃倒序 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam String merchantId,
                                          @RequestParam(defaultValue = "default") String userId,
                                          @RequestParam(defaultValue = "50") int limit) {
        return conversationRepository.listByUser(merchantId, userId, limit).stream()
                .map(ConversationController::toView)
                .toList();
    }

    /** 会话历史轮次：run 型轮次的回答取 analysis_run.conclusion，无 run 轮次取 turn.answer */
    @GetMapping("/{sessionId}/turns")
    public List<ConversationRepository.TurnRecord> turns(@PathVariable String sessionId) {
        return conversationRepository.listTurns(sessionId);
    }

    private static Map<String, Object> toView(SessionRecord s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("session_id", s.sessionId());
        v.put("title", s.title());
        v.put("topic_summary", s.topicSummary());
        v.put("status", s.status());
        v.put("last_active_at", s.lastActiveAt());
        v.put("created_at", s.createdAt());
        return v;
    }
}
