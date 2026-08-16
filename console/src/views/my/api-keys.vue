<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <span class="title">我的 API Key</span>
          <span class="desc">归属我自己的访问凭证列表（创建/管理请到管理端）</span>
        </div>
      </template>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="keyName" label="Key 名称" width="180">
          <template #default="{ row }">{{ row.keyName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="accessKey" label="Access Key" min-width="220">
          <template #default="{ row }">
            <code class="ak">{{ row.accessKey }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="expireAt" label="过期时间" width="170">
          <template #default="{ row }">{{ row.expireAt || '永久' }}</template>
        </el-table-column>
        <el-table-column prop="lastUsedAt" label="最近使用" width="170">
          <template #default="{ row }">{{ row.lastUsedAt || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" />
      </el-table>

      <el-pagination
        class="pagination"
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="loadList"
        @size-change="loadList"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { listMyApiKeys } from '@/api/my'
import type { ApiKey } from '@/api/apikey'

const loading = ref(false)
const list = ref<ApiKey[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 10 })

function statusText(s?: string) {
  return { ENABLED: '启用', DISABLED: '停用', EXPIRED: '过期', REVOKED: '已吊销' }[s || ''] || s || '-'
}
function statusTag(s?: string) {
  return { ENABLED: 'success', DISABLED: 'info', EXPIRED: 'warning', REVOKED: 'danger' }[s || ''] || 'info'
}

async function loadList() {
  loading.value = true
  try {
    const res = await listMyApiKeys(query)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

onMounted(loadList)
</script>

<style scoped lang="scss">
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .title {
    font-size: 16px;
    font-weight: 600;
  }

  .desc {
    margin-left: 12px;
    color: #909399;
    font-size: 13px;
  }
}

.ak {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #606266;
}

.pagination {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
