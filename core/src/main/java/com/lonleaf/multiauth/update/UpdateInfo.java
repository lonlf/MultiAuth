package com.lonleaf.multiauth.update;

/**
 * 更新检查结果。
 */
public record UpdateInfo(String latestVersion, String publishedAt, String releaseUrl, boolean newer) {
}
