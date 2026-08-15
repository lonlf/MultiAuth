package com.lonleaf.multiauth.update;

public record UpdateInfo(String latestVersion, String publishedAt, String releaseUrl, boolean newer) {
}
