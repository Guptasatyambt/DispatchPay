package org.myorg.dispatchpay.platform.pagination;

import java.util.List;

/**
 * A page of a history read with an opaque cursor: pass {@code nextCursor} back as {@code ?cursor=}
 * for the following page; it is {@code null} on the last page. Unlike offset pages, rows added
 * meanwhile never shift or repeat rows across pages.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public static final int DEFAULT_SIZE = PageQuery.DEFAULT_SIZE;
    public static final int MAX_SIZE = PageQuery.MAX_SIZE;
}
