package com.qqmu.jargus.util;

/**
 * 分页参数收敛：页码至少 1，每页条数有上限。
 * 分页参数直接来自 URL 查询串，不设上限时 size=10 亿会拉全表进内存（认证用户即可打爆堆）。
 */
public final class PageUtils {

    /** 单页条数上限（页面最大只用到 100，留一倍余量给接口调用方） */
    public static final int MAX_PAGE_SIZE = 200;

    private PageUtils() {
    }

    public static int pageNum(int pageNum) {
        return Math.max(1, pageNum);
    }

    public static int pageSize(int pageSize) {
        return Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
    }
}
