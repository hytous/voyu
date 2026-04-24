package com.voyu.agent.service.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 负责 plan markdown 文件的持久化管理。
 * <p>
 * 参考 Claude Code 的 plan mode，每个 session 会产出一份 plan.md 文件，
 * 记录规划思路和任务清单，便于回溯和前端展示。
 */
@Service
public class PlanFileService {

    private static final Logger log = LoggerFactory.getLogger(PlanFileService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Path planDir;

    public PlanFileService(@Value("${voyu.agent.plan-dir:data/plans}") String planDirPath) {
        this.planDir = Path.of(planDirPath);
        try {
            Files.createDirectories(this.planDir);
        } catch (IOException ex) {
            log.warn("无法创建 plan 文件目录: {}", planDirPath, ex);
        }
    }

    /**
     * 创建或覆写 plan 文件。
     *
     * @return 文件绝对路径
     */
    public String createPlanFile(String sessionId, String content) {
        Path filePath = resolvePath(sessionId);
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Plan 文件已创建: {}", filePath);
            return filePath.toAbsolutePath().toString();
        } catch (IOException ex) {
            log.error("创建 plan 文件失败: {}", filePath, ex);
            return "";
        }
    }

    /**
     * 追加内容到 plan 文件。
     */
    public void appendToPlanFile(String sessionId, String content) {
        Path filePath = resolvePath(sessionId);
        try {
            if (!Files.exists(filePath)) {
                createPlanFile(sessionId, content);
                return;
            }
            Files.writeString(filePath, "\n" + content, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.error("追加 plan 文件失败: {}", filePath, ex);
        }
    }

    /**
     * 读取 plan 文件全部内容。
     */
    public String readPlanFile(String sessionId) {
        Path filePath = resolvePath(sessionId);
        try {
            if (!Files.exists(filePath)) {
                return "";
            }
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.error("读取 plan 文件失败: {}", filePath, ex);
            return "";
        }
    }

    /**
     * 删除 plan 文件。
     */
    public boolean deletePlanFile(String sessionId) {
        Path filePath = resolvePath(sessionId);
        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.info("Plan 文件已删除: {}", filePath);
                return true;
            }
            return false;
        } catch (IOException ex) {
            log.error("删除 plan 文件失败: {}", filePath, ex);
            return false;
        }
    }

    /**
     * 检查 plan 文件是否存在。
     */
    public boolean exists(String sessionId) {
        return Files.exists(resolvePath(sessionId));
    }

    /**
     * 获取 plan 文件路径。
     */
    public String getPlanFilePath(String sessionId) {
        return resolvePath(sessionId).toAbsolutePath().toString();
    }

    /**
     * 构建 plan markdown 内容。
     */
    public String buildPlanMarkdown(String sessionId,
                                     String mission,
                                     String plannerThought,
                                     String userMessage,
                                     String destination,
                                     String departure,
                                     String travelDays,
                                     String budget,
                                     String preferences,
                                     java.util.List<String> taskLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 旅游规划任务书\n\n");
        sb.append("- **Session**: ").append(sessionId).append("\n");
        sb.append("- **创建时间**: ").append(TIMESTAMP_FORMAT.format(Instant.now())).append("\n\n");

        sb.append("## 用户需求\n\n");
        sb.append("- **原始消息**: ").append(blankAs(userMessage, "未说明")).append("\n");
        sb.append("- **目的地**: ").append(blankAs(destination, "未说明")).append("\n");
        sb.append("- **出发地**: ").append(blankAs(departure, "未说明")).append("\n");
        sb.append("- **天数**: ").append(blankAs(travelDays, "未说明")).append("\n");
        sb.append("- **预算**: ").append(blankAs(budget, "未说明")).append("\n");
        sb.append("- **偏好**: ").append(blankAs(preferences, "未说明")).append("\n\n");

        sb.append("## 规划任务\n\n");
        sb.append("**Mission**: ").append(blankAs(mission, "生成供执行的工具执行计划")).append("\n\n");

        sb.append("## 规划思路\n\n");
        sb.append(blankAs(plannerThought, "基于用户需求制定工具执行计划")).append("\n\n");

        sb.append("## 任务清单\n\n");
        if (taskLines == null || taskLines.isEmpty()) {
            sb.append("- [ ] 暂无任务\n");
        } else {
            for (String taskLine : taskLines) {
                sb.append("- [ ] ").append(taskLine).append("\n");
            }
        }
        sb.append("\n");

        sb.append("## 执行记录\n\n");
        sb.append("_（执行阶段动态更新）_\n");

        return sb.toString();
    }

    private Path resolvePath(String sessionId) {
        String safeId = sessionId.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        return planDir.resolve("plan-" + safeId + ".md");
    }

    private String blankAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
