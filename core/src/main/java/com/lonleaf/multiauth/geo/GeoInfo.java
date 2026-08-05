package com.lonleaf.multiauth.geo;

/**
 * IP 地理位置信息。
 * @param country 国家名称
 * @param province 省份/州
 * @param city 城市
 * @param countryCode 国家代码（如 CN, US）
 */
public record GeoInfo(String country, String province, String city, String countryCode) {}
