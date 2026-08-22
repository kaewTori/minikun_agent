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
            "(แม่นยำ|ชัดเจน|ตรวจสอบ|เปรียบเทียบ|ตัวเลข|ข้อผิดพลาด|"
                    + "exact|accurate|verify|fact.?check|precise|compare|calculate|number|error|debug)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CALCULATION_REQUEST = Pattern.compile(
            "(คำนวณ|คิดเลข|หาค่า|สูตร|ประมาณการ|ประเมินทรัพยากร|แบ่งทรัพยากร|"
                    + "ผลบวก|ผลลบ|ผลคูณ|ผลหาร|บวกเลข|ลบเลข|คูณเลข|หารเลข|capacity.?planning|"
                    + "calculat(?:e|ion)|comput(?:e|ation)|formula|arithmetic|estimate|sizing|"
                    + "\\d+(?:[.,]\\d+)?\\s*(?:%|(?:kb|mb|gb|tb|kib|mib|gib|tib|ms|s|apps?|applications?|"
                    + "instances?)\\b|วินาที|นาที|ชั่วโมง|แอป|อินสแตนซ์)|"
                    + "\\d+(?:[.,]\\d+)?\\s*[-+*/]\\s*\\d+(?:[.,]\\d+)?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TECHNICAL_WORK = Pattern.compile(
            "(โค้ด|เขียนโปรแกรม|โปรแกรมมิ่ง|ซอฟต์แวร์|ไมโครเซอร์วิส|เซิร์ฟเวอร์|ฐานข้อมูล|อัลกอริทึม|"
                    + "สถาปัตยกรรม(?:ระบบ)?|ออกแบบระบบ|ประสิทธิภาพ|หน่วยความจำ|ฮีป|เธรด|ดีพลอย|ตั้งค่า.*(?:java|jvm|app)|"
                    + "\\b(?:code|coding|programming|software|algorithm|logic|function|method|class|api|endpoint|"
                    + "query|sql|database|java\\d*|jdk\\d*|jvm|spring(?:\\s*boot)?|micro\\s*services?|apps?|applications?|"
                    + "framework|libraries?|dependencies?|maven|gradle|git|ci.?cd|pipeline|docker|kubernetes|k8s|"
                    + "server|backend|frontend|architecture|system.?design|ram\\d*|memory|heap|gc|thread|concurren(?:cy|t)|"
                    + "performance|latency|throughput|deploy(?:ment)?|bare.?metal|config(?:ure|uration)?|compiler|"
                    + "bug|debug|exception|stack.?trace|unit.?test|integration.?test)\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CREATIVE_REQUEST = Pattern.compile(
            "(แต่งเรื่อง|แต่งนิยาย|เขียนนิยาย|เรื่องสั้น|นิยาย|ฟิค|บทละคร|บทกวี|กลอน|กวี|สวมบทบาท|โลกสมมติ|"
                    + "creative writing|write a story|write fiction|short story|novel|fanfic|roleplay|poem|"
                    + "poetry|screenplay|fictional|worldbuilding)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CASUAL_CONVERSATION = Pattern.compile(
            "(สวัสดี|หวัดดี|เป็น(?:ยัง)?ไง|ขอบคุณ|ขอบใจ|ฝันดี|คิดถึง|เหงา|เหนื่อย|เครียด|เศร้า|ดีใจ|"
                    + "ไม่สบายใจ|คุย(?:กัน|เล่น|เป็นเพื่อน|แบบ)|คู่หู|เพื่อนคุย|companion|hello|hi\\b|"
                    + "thanks|thank you|how are you|i feel|i['’]?m (?:tired|sad|lonely|stressed))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public CooperationRoutingDecision decide(String userText) {
        if (userText == null || userText.isBlank()) {
            return CooperationRoutingDecision.low();
        }
        String value = userText.trim().toLowerCase(Locale.ROOT);
        if (CREATIVE_REQUEST.matcher(value).find()) {
            return CooperationRoutingDecision.creative();
        }
        if (HIGH_RISK.matcher(value).find()) {
            return new CooperationRoutingDecision(CooperationRisk.HIGH, true, "high_risk_domain");
        }
        if (CALCULATION_REQUEST.matcher(value).find()) {
            return new CooperationRoutingDecision(CooperationRisk.MEDIUM, true, "calculation_request");
        }
        if (TECHNICAL_WORK.matcher(value).find()) {
            return new CooperationRoutingDecision(CooperationRisk.MEDIUM, true, "technical_work");
        }
        if (CASUAL_CONVERSATION.matcher(value).find()) {
            return CooperationRoutingDecision.low();
        }
        if (MEDIUM_RISK.matcher(value).find() || value.length() > 600) {
            return new CooperationRoutingDecision(CooperationRisk.MEDIUM, true, "precision_signal");
        }
        return CooperationRoutingDecision.low();
    }
}
