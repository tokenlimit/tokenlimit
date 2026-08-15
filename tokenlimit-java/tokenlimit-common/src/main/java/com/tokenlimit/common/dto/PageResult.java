package com.tokenlimit.common.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果.
 *
 * @param <T> 数据类型
 */
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页 */
    private long page;
    /** 每页大小 */
    private long size;
    /** 总条数 */
    private long total;
    /** 数据列表 */
    private List<T> records;

    public PageResult() {
    }

    public PageResult(long page, long size, long total, List<T> records) {
        this.page = page;
        this.size = size;
        this.total = total;
        this.records = records;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }
}
