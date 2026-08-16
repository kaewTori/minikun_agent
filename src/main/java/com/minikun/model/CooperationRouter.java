package com.minikun.model;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/** Cheap, deterministic first-pass router for TinyGrad escalation. */
@Service
public final class CooperationRouter {
    private static final Pattern HIGH_RISK = Pattern.compile(
            "(สุขภาพ|ยา|การแพทย์|วินิจฉัย|การเงิน|ลงทุน|ภาษี|กฎหมาย|ทนาย|สัญญา|รหัสผ่าน|secret|password|"
                    + "medical|health|medicine|diagnos|finance|financial|investment|tax|legal|lawyer|contract|"
                    + "credential|security|exploit)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MEDIUM_RISK = Pattern.compile(
            "(ล่าสุด|ปัจจุบัน|วันนี้|แม่นยำ|ชัดเจน|ตรวจสอบ|อ้างอิง|เปรียบเทียบ|คำนวณ|ตัวเลข|โค้ด|ข้อผิดพลาด|"
                    + "exact|accurate|verify|fact.?check|current|latest|today|precise|compare|calculate|number|"
                    + "code|error|debug|source|citation|why|how to)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public CooperationRoutingDecision decide(String userText) {
        if (userText == null || userText.isBlank()) {
            return CooperationRoutingDecision.low();
        }
        String value = userText.trim().toLowerCase(Locale.ROOT);
        if (HIGH_RISK.matcher(value).find()) {
            return new CooperationRoutingDecision(CooperationRisk.HIGH, true, "high_risk_domain");
        }
        if (MEDIUM_RISK.matcher(value).find()
                || value.contains("?") || value.contains("？") || value.length() > 600) {
            return new CooperationRoutingDecision(CooperationRisk.MEDIUM, true, "precision_signal");
        }
        return CooperationRoutingDecision.low();
    }
}
