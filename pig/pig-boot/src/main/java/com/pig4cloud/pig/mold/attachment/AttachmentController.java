/*
 * 模具加工管理系统 M7-A / A4 附件接口（契约 6.10.1，5 操作）。
 *
 *   POST   /mold/attachments                        mold:attachment:upload
 *   GET    /mold/attachments?ownerType=&ownerId=     mold:attachment:download
 *   GET    /mold/attachments/{attachmentId}          mold:attachment:download
 *   GET    /mold/attachments/{attachmentId}/download mold:attachment:download（二进制）
 *   DELETE /mold/attachments/{attachmentId}          mold:attachment:delete（可选 {reason}，恒 204）
 */
package com.pig4cloud.pig.mold.attachment;

import com.pig4cloud.pig.common.core.util.R;
import com.pig4cloud.pig.mold.web.IdempotencyService;
import com.pig4cloud.pig.mold.web.MoldApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mold/attachments")
public class AttachmentController {

	private static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private final AttachmentService attachmentService;

	private final IdempotencyService idempotencyService;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("@pms.hasPermission('mold:attachment:upload')")
	public R<Map<String, Object>> upload(@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestParam(value = "file", required = false) MultipartFile file,
			@RequestParam(required = false) String ownerType, @RequestParam(required = false) String ownerId,
			@RequestParam(required = false) String category, @RequestParam(required = false) String sensitivity,
			HttpServletRequest request, HttpServletResponse response) {
		requireSingleFilePart(request, file);
		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (IOException ex) {
			throw new MoldApiException(500, "INTERNAL_ERROR", "读取上传文件失败");
		}
		if (bytes.length == 0) {
			throw MoldApiException.validation("/body/file", "required", null, "文件不能为空");
		}
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("ownerType", ownerType);
		meta.put("ownerId", ownerId);
		meta.put("category", category);
		meta.put("sensitivity", sensitivity);
		meta.put("fileName", file.getOriginalFilename());
		meta.put("sizeBytes", bytes.length);
		meta.put("sha256", sha256(bytes));
		IdempotencyService.Outcome outcome = idempotencyService.executeRequired("attachment.upload", key,
				"POST /mold/attachments", meta, 201,
				() -> attachmentService.upload(bytes, file.getContentType(), file.getOriginalFilename(), ownerType,
						ownerId, category, sensitivity));
		if (outcome.replayed()) {
			response.setHeader(REPLAYED_HEADER, "true");
		}
		return R.ok(outcome.data());
	}

	@GetMapping
	@PreAuthorize("@pms.hasPermission('mold:attachment:download')")
	public R<Map<String, Object>> list(@RequestParam(required = false) String ownerType,
			@RequestParam(required = false) String ownerId) {
		return R.ok(attachmentService.list(ownerType, ownerId));
	}

	@GetMapping("/{attachmentId}")
	@PreAuthorize("@pms.hasPermission('mold:attachment:download')")
	public R<Map<String, Object>> get(@PathVariable String attachmentId) {
		return R.ok(attachmentService.get(attachmentId));
	}

	@GetMapping("/{attachmentId}/download")
	@PreAuthorize("@pms.hasPermission('mold:attachment:download')")
	public ResponseEntity<byte[]> download(@PathVariable String attachmentId) {
		AttachmentService.Download download = attachmentService.download(attachmentId);
		String encoded = URLEncoder.encode(download.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(download.mimeType()))
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
			.header("X-Content-Type-Options", "nosniff")
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(download.bytes());
	}

	@DeleteMapping("/{attachmentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("@pms.hasPermission('mold:attachment:delete')")
	public void delete(@PathVariable String attachmentId,
			@RequestBody(required = false) Map<String, Object> body) {
		attachmentService.delete(attachmentId, body);
	}

	private static void requireSingleFilePart(HttpServletRequest request, MultipartFile file) {
		if (file == null) {
			throw MoldApiException.validation("/body/file", "required", null, "必须提供 file 字段");
		}
		if (request instanceof MultipartHttpServletRequest multipart) {
			long total = multipart.getMultiFileMap().values().stream().mapToLong(List::size).sum();
			if (total != 1 || multipart.getMultiFileMap().size() != 1
					|| !multipart.getMultiFileMap().containsKey("file")) {
				throw MoldApiException.validation("/body/file", "const", Map.of("field", "file"), "只允许一个 file 文件字段");
			}
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			StringBuilder hex = new StringBuilder(64);
			for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
		catch (Exception ex) {
			throw new IllegalStateException("SHA-256 不可用", ex);
		}
	}

}
