/*
 * 模具加工管理系统 M7-A / A4 附件（契约 6.10.1）
 *
 * 不变量：
 *  - 判定顺序写死：① MIME/内容探测 + 归属对象存在性与写权限 → ② 数量配额 → ③ 单文件体积；
 *  - 数量/配额超限 422 ATTACHMENT_LIMIT，单文件体积超限 413 UPLOAD_TOO_LARGE（G-3）；
 *  - 财务归属/财务类别强制 sensitivity=FINANCIAL，上传/下载需 mold:cash:create / mold:cash:list；
 *  - 软删同时删除磁盘文件，deleted_reason 有则写、无则 NULL，响应恒 204（G-4）。
 */
package com.pig4cloud.pig.mold.attachment;

import com.pig4cloud.pig.common.security.component.PermissionService;
import com.pig4cloud.pig.mold.audit.AuditWriter;
import com.pig4cloud.pig.mold.event.EventWriter;
import com.pig4cloud.pig.mold.security.MoldActor;
import com.pig4cloud.pig.mold.security.MoldActorResolver;
import com.pig4cloud.pig.mold.web.MoldApiException;
import com.pig4cloud.pig.mold.web.MoldFields;
import com.pig4cloud.pig.mold.web.MoldTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

	public static final Set<String> OWNER_TYPES = Set.of("PROJECT", "STAGE", "ISSUE", "PURCHASE", "PURCHASE_RECEIPT",
			"PURCHASE_INVOICE", "CASH_ENTRY");

	public static final Set<String> CATEGORIES = Set.of("PURCHASE_ORDER", "DELIVERY_NOTE", "INVOICE",
			"PAYMENT_VOUCHER", "CONTRACT", "OTHER");

	public static final Set<String> SENSITIVITIES = Set.of("NORMAL", "FINANCIAL");

	public static final Set<String> FINANCIAL_OWNERS = Set.of("PURCHASE", "PURCHASE_RECEIPT", "PURCHASE_INVOICE",
			"CASH_ENTRY");

	public static final Set<String> FINANCIAL_CATEGORIES = Set.of("PURCHASE_ORDER", "DELIVERY_NOTE", "INVOICE",
			"PAYMENT_VOUCHER", "CONTRACT");

	public static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png", "image/heic", "application/pdf");

	public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

	public static final int MAX_ATTACHMENTS_PER_OWNER = 20;

	private final JdbcTemplate jdbcTemplate;

	private final MoldActorResolver actorResolver;

	private final AuditWriter auditWriter;

	private final EventWriter eventWriter;

	private final PermissionService permissionService;

	@Value("${file.local.base-path:/tmp/pig-m2-files}")
	private String basePath;

	// ------------------------------------------------------------------ 上传

	@Transactional(rollbackFor = Exception.class)
	public Map<String, Object> upload(byte[] bytes, String declaredMime, String originalName, String ownerTypeRaw,
			String ownerIdRaw, String categoryRaw, String sensitivityRaw) {
		MoldActor actor = actorResolver.current();

		// ① MIME/内容探测 + 归属对象存在性与写权限
		String ownerType = MoldFields.enumValue(ownerTypeRaw, "ownerType", OWNER_TYPES);
		String ownerId = requireUuid(ownerIdRaw, "ownerId");
		String category = MoldFields.enumValue(categoryRaw, "category", CATEGORIES);
		if (bytes == null || bytes.length == 0) {
			throw MoldApiException.validation("/body/file", "required", null, "文件不能为空");
		}
		String detectedMime = detectMime(bytes);
		if (detectedMime == null || !ALLOWED_MIME.contains(detectedMime)
				|| (StringUtils.hasText(declaredMime) && !detectedMime.equalsIgnoreCase(declaredMime))) {
			throw new MoldApiException(422, "FILE_TYPE_NOT_ALLOWED", "文件内容与允许类型不符");
		}

		boolean financial = FINANCIAL_OWNERS.contains(ownerType) || FINANCIAL_CATEGORIES.contains(category);
		Owner owner = resolveOwner(ownerType, ownerId);
		if (owner.missing()) {
			throw new MoldApiException(422, "ATTACHMENT_OWNER_INVALID", "附件归属对象不存在或已失效");
		}
		// 归属写权限（2026-11-13 拍板）：OWNER 直接放行；采购/财务按对象权限；
		// 其余按项目负责人 / 阶段负责人 / 异常报告人或处理人。财务下限再叠加 mold:cash:create。
		requireOwnerWrite(ownerType, owner, actor);
		if (financial) {
			requireCapability("mold:cash:create");
		}

		String sensitivity = financial ? "FINANCIAL"
				: (StringUtils.hasText(sensitivityRaw)
						? MoldFields.enumValue(sensitivityRaw, "sensitivity", SENSITIVITIES)
						: "NORMAL");

		// ② 数量配额
		Long current = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM attachments WHERE owner_type = ? AND owner_id = ? AND status = 'READY'",
				Long.class, ownerType, ownerId);
		long currentValue = current == null ? 0L : current;
		if (currentValue >= MAX_ATTACHMENTS_PER_OWNER) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("limit", (int) MAX_ATTACHMENTS_PER_OWNER);
			details.put("current", (int) currentValue);
			details.put("ownerType", ownerType);
			details.put("ownerId", ownerId);
			throw new MoldApiException(422, "ATTACHMENT_LIMIT", "该对象附件已达上限", details);
		}

		// ③ 单文件体积
		if (bytes.length > MAX_UPLOAD_BYTES) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("maxBytes", (int) MAX_UPLOAD_BYTES);
			details.put("sizeBytes", bytes.length);
			throw new MoldApiException(413, "UPLOAD_TOO_LARGE", "文件超过 10 MiB 上限", details);
		}

		String fileName = sanitizeName(originalName);
		String sha256 = sha256(bytes);
		String storageKey = UUID.randomUUID().toString();
		Path target = storagePath(storageKey);
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
		}
		catch (IOException ex) {
			throw new MoldApiException(500, "INTERNAL_ERROR", "写入附件存储失败");
		}

		try {
			jdbcTemplate.update("""
					INSERT INTO attachments
						(owner_type, owner_id, project_id, category, sensitivity, file_name, mime_type,
						 size_bytes, sha256, storage_key, uploaded_by)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", ownerType, ownerId, owner.projectId(), category, sensitivity, fileName, detectedMime,
					bytes.length, sha256, storageKey, actor.userId());
			Map<String, Object> created = jdbcTemplate.queryForList(
					"SELECT * FROM attachments WHERE storage_key = ?", storageKey).get(0);
			Map<String, Object> dto = dto(created);
			auditWriter.record("ATTACHMENT", (String) dto.get("id"), "UPLOAD", null, dto);
			eventWriter.emit("attachment.created", "ATTACHMENT", (String) dto.get("id"),
					(String) dto.get("projectId"), null, actor.userId(), 1,
					"FINANCIAL".equals(sensitivity) ? "FINANCE" : "BASIC", null, null, List.of("fileName", "sha256"));
			return dto;
		}
		catch (RuntimeException ex) {
			try {
				Files.deleteIfExists(target);
			}
			catch (IOException ignored) {
				log.warn("附件回滚清理失败 storageKey={}", storageKey);
			}
			throw ex;
		}
	}

	// ------------------------------------------------------------------ 查询

	public Map<String, Object> list(String ownerTypeRaw, String ownerIdRaw) {
		MoldActor actor = actorResolver.current();
		String ownerType = MoldFields.enumValue(ownerTypeRaw, "ownerType", OWNER_TYPES);
		String ownerId = requireUuid(ownerIdRaw, "ownerId");
		List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
				SELECT * FROM attachments
				WHERE owner_type = ? AND owner_id = ? AND status = 'READY'
				ORDER BY uploaded_at, id
				""", ownerType, ownerId);
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> row : rows) {
			if (isFinancial(row) && !canReadFinancial(row, actor)) {
				continue;
			}
			result.add(dto(row));
		}
		return com.pig4cloud.pig.mold.web.MoldResponses.collection(result, dataVersion());
	}

	public Map<String, Object> get(String attachmentId) {
		Map<String, Object> row = load(attachmentId);
		requireVisible(row);
		return dto(row);
	}

	@Transactional(rollbackFor = Exception.class)
	public Download download(String attachmentId) {
		Map<String, Object> row = load(attachmentId);
		requireVisible(row);
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(storagePath((String) row.get("storage_key")));
		}
		catch (IOException ex) {
			throw new MoldApiException(500, "INTERNAL_ERROR", "读取附件失败");
		}
		if (!sha256(bytes).equals(row.get("sha256"))) {
			log.error("附件完整性校验失败 id={}", attachmentId);
			throw new MoldApiException(500, "INTERNAL_ERROR", "附件完整性校验失败");
		}
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("id", attachmentId);
		auditWriter.record("ATTACHMENT", attachmentId, "DOWNLOAD", null, after);
		return new Download(bytes, (String) row.get("mime_type"), (String) row.get("file_name"));
	}

	// ------------------------------------------------------------------ 删除

	@Transactional(rollbackFor = Exception.class)
	public void delete(String attachmentId, Map<String, Object> body) {
		MoldActor actor = actorResolver.current();
		String reason = null;
		if (body != null) {
			MoldFields.rejectUnknownFields(body, Set.of("reason"));
			if (body.get("reason") != null) {
				reason = MoldFields.text(body.get("reason"), "reason", 500);
			}
		}
		Map<String, Object> row = load(attachmentId);
		requireVisible(row);
		if (!actor.userId().equals(row.get("uploaded_by")) && !actor.roles().contains("OWNER")) {
			throw new MoldApiException(403, "ATTACHMENT_ACCESS_DENIED", "只允许上传者本人或 OWNER 删除附件");
		}
		Map<String, Object> before = dto(row);
		jdbcTemplate.update("""
				UPDATE attachments
				   SET status = 'DELETED', deleted_at = UTC_TIMESTAMP(3), deleted_by = ?, deleted_reason = ?
				 WHERE id = ? AND status = 'READY'
				""", actor.userId(), reason, attachmentId);
		Map<String, Object> after = dto(jdbcTemplate.queryForList(
				"SELECT * FROM attachments WHERE id = ?", attachmentId).get(0));
		auditWriter.record("ATTACHMENT", attachmentId, "DELETE", before, after);
		eventWriter.emit("attachment.deleted", "ATTACHMENT", attachmentId, (String) row.get("project_id"), null,
				actor.userId(), null, "FINANCIAL".equals(row.get("sensitivity")) ? "FINANCE" : "BASIC", "DELETED",
				"READY", List.of("status"));
		String storageKey = (String) row.get("storage_key");
		try {
			Files.deleteIfExists(storagePath(storageKey));
		}
		catch (IOException | RuntimeException ex) {
			log.warn("附件软删后清理磁盘文件失败 id={} storageKey={}", attachmentId, storageKey, ex);
		}
	}

	public record Download(byte[] bytes, String mimeType, String fileName) {
	}

	// ------------------------------------------------------------------ 内部

	private Map<String, Object> load(String attachmentId) {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT * FROM attachments WHERE id = ? AND status = 'READY'", attachmentId);
		if (rows.isEmpty()) {
			throw new MoldApiException(404, "NOT_FOUND", "附件不存在");
		}
		return rows.get(0);
	}

	private void requireVisible(Map<String, Object> row) {
		if (isFinancial(row) && !canReadFinancial(row, actorResolver.current())) {
			throw new MoldApiException(403, "ATTACHMENT_ACCESS_DENIED", "财务附件需要财务读权限");
		}
	}

	private static boolean isFinancial(Map<String, Object> row) {
		return "FINANCIAL".equals(row.get("sensitivity")) || FINANCIAL_CATEGORIES.contains(row.get("category"));
	}

	/**
	 * 财务附件：OWNER/FINANCE 可读全部；其他人只能读自己录入流水的附件。
	 */
	private boolean canReadFinancial(Map<String, Object> row, MoldActor actor) {
		if (permissionService.hasPermission("mold:cash:viewAll")) {
			return true;
		}
		if (!"CASH_ENTRY".equals(row.get("owner_type"))) {
			return false;
		}
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM cash_entries WHERE id = ? AND created_by = ?", Long.class,
				row.get("owner_id"), actor.userId());
		return count != null && count > 0;
	}

	private Owner resolveOwner(String ownerType, String ownerId) {
		List<Map<String, Object>> rows;
		switch (ownerType) {
			case "PROJECT" -> rows = jdbcTemplate.queryForList(
					"SELECT id, id AS project_id, owner_user_id FROM projects WHERE id = ? AND deleted_at IS NULL", ownerId);
			case "STAGE" -> rows = jdbcTemplate.queryForList("""
					SELECT s.id, s.project_id, s.responsible_user_id, p.owner_user_id
					FROM project_stages s JOIN projects p ON p.id = s.project_id
					WHERE s.id = ? AND p.deleted_at IS NULL
					""", ownerId);
			case "ISSUE" -> rows = jdbcTemplate.queryForList("""
					SELECT i.id, i.project_id, i.reporter_id, i.handler_id, p.owner_user_id
					FROM issues i JOIN projects p ON p.id = i.project_id
					WHERE i.id = ? AND p.deleted_at IS NULL
					""", ownerId);
			case "PURCHASE" -> rows = jdbcTemplate.queryForList(
					"SELECT id, project_id FROM purchases WHERE id = ?", ownerId);
			case "PURCHASE_RECEIPT" -> rows = jdbcTemplate.queryForList("""
					SELECT r.id, p.project_id FROM purchase_receipts r
					JOIN purchases p ON p.id = r.purchase_id WHERE r.id = ?
					""", ownerId);
			case "PURCHASE_INVOICE" -> rows = jdbcTemplate.queryForList("""
					SELECT i.id, p.project_id FROM purchase_invoices i
					JOIN purchases p ON p.id = i.purchase_id WHERE i.id = ?
					""", ownerId);
			case "CASH_ENTRY" -> rows = jdbcTemplate.queryForList(
					"SELECT id, project_id FROM cash_entries WHERE id = ?", ownerId);
			default -> rows = List.of();
		}
		if (rows.isEmpty()) {
			return Owner.missingOwner();
		}
		return new Owner(false, rows.get(0));
	}

	private void requireOwnerWrite(String ownerType, Owner owner, MoldActor actor) {
		// OWNER 对任意归属对象都有写权限（2026-11-13 拍板，06 实测缺口）。
		if (actor.roles().contains("OWNER")) {
			return;
		}
		Map<String, Object> row = owner.row();
		switch (ownerType) {
			case "PURCHASE", "PURCHASE_RECEIPT", "PURCHASE_INVOICE" -> requireCapability("mold:purchase:update");
			case "CASH_ENTRY" -> requireCapability("mold:cash:create");
			default -> {
				boolean allowed = switch (ownerType) {
					case "PROJECT" -> actor.userId().equals(row.get("owner_user_id"));
					case "STAGE" -> actor.userId().equals(row.get("owner_user_id"))
							|| actor.userId().equals(row.get("responsible_user_id"));
					case "ISSUE" -> actor.userId().equals(row.get("owner_user_id"))
							|| actor.userId().equals(row.get("reporter_id"))
							|| actor.userId().equals(row.get("handler_id"));
					default -> false;
				};
				if (!allowed) {
					throw new MoldApiException(403, "ATTACHMENT_ACCESS_DENIED", "无权向该归属对象上传附件");
				}
			}
		}
	}

	private void requireCapability(String permission) {
		if (!permissionService.hasPermission(permission)) {
			throw new MoldApiException(403, "FORBIDDEN", "无此操作权限");
		}
	}

	private String dataVersion() {
		Long version = jdbcTemplate.queryForObject("SELECT version FROM event_clock WHERE singleton = 1", Long.class);
		return String.valueOf(version == null ? 0L : version);
	}

	private Path storagePath(String storageKey) {
		if (storageKey == null || !storageKey.matches("[0-9a-fA-F-]{36}")) {
			throw new IllegalStateException("非法存储键");
		}
		return Path.of(basePath).resolve(storageKey.substring(0, 2)).resolve(storageKey);
	}

	private static String requireUuid(String raw, String field) {
		if (!StringUtils.hasText(raw)) {
			throw MoldApiException.validation("/body/" + field, "required", null, field + " 必填");
		}
		try {
			return UUID.fromString(raw.trim()).toString();
		}
		catch (IllegalArgumentException ex) {
			throw MoldApiException.validation("/body/" + field, "format", Map.of("format", "uuid"), "参数无效 " + field);
		}
	}

	private static String sanitizeName(String original) {
		String name = original == null ? "file" : original;
		name = name.replaceAll("\\\\", "/");
		int slash = name.lastIndexOf('/');
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}
		name = name.replaceAll("[\\x00-\\x1f\\x7f]", "_").trim();
		if (name.isEmpty()) {
			name = "file";
		}
		return name.length() > 255 ? name.substring(name.length() - 255) : name;
	}

	private static String sha256(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			StringBuilder hex = new StringBuilder(64);
			for (byte b : digest.digest(bytes)) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
		catch (Exception ex) {
			throw new IllegalStateException("SHA-256 不可用", ex);
		}
	}

	/**
	 * 按文件内容探测类型，不信任客户端声明的 Content-Type。
	 */
	static String detectMime(byte[] bytes) {
		if (bytes.length >= 4 && bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46) {
			return "application/pdf";
		}
		if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
			return "image/jpeg";
		}
		if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E
				&& bytes[3] == 0x47 && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
			return "image/png";
		}
		if (bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
			String brand = new String(bytes, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
			if (Set.of("heic", "heix", "hevc", "hevx", "mif1", "msf1").contains(brand)) {
				return "image/heic";
			}
		}
		return null;
	}

	private static Map<String, Object> dto(Map<String, Object> row) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("id", row.get("id"));
		dto.put("ownerType", row.get("owner_type"));
		dto.put("ownerId", row.get("owner_id"));
		dto.put("projectId", row.get("project_id"));
		dto.put("category", row.get("category"));
		dto.put("sensitivity", row.get("sensitivity"));
		dto.put("fileName", row.get("file_name"));
		dto.put("mimeType", row.get("mime_type"));
		dto.put("sizeBytes", number(row.get("size_bytes")));
		dto.put("sha256", row.get("sha256"));
		dto.put("uploadedBy", row.get("uploaded_by"));
		dto.put("uploadedAt", MoldTime.iso(row.get("uploaded_at")));
		return dto;
	}

	private static Object number(Object value) {
		if (value instanceof BigDecimal decimal) {
			long longValue = decimal.longValue();
			return longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE ? (Object) (int) longValue
					: (Object) longValue;
		}
		if (value instanceof Long longValue) {
			return longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE ? (Object) longValue.intValue()
					: (Object) longValue;
		}
		return value;
	}

	private record Owner(boolean missing, Map<String, Object> row) {

		static Owner missingOwner() {
			return new Owner(true, null);
		}

		String projectId() {
			return row == null ? null : (String) row.get("project_id");
		}

	}
}
