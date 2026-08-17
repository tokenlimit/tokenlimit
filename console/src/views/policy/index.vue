<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <div class="header-left">
            <span class="title">团队模型策略</span>
            <span class="desc">配置团队 + 模型使用哪个 Provider 凭证进行转发</span>
          </div>
          <el-button type="primary" @click="openDialog()">新建策略</el-button>
        </div>
      </template>

      <div class="toolbar">
        <el-select v-model="query.teamCode" placeholder="团队编码" clearable style="width: 160px" @change="loadList">
          <el-option v-for="t in teamCodes" :key="t" :label="t" :value="t" />
        </el-select>
        <el-input v-model="query.model" placeholder="模型名称" clearable style="width: 180px" @keyup.enter="loadList" @clear="loadList" />
        <el-input v-model="query.keyword" placeholder="搜索团队或凭证编码" clearable style="width: 220px" @keyup.enter="loadList" @clear="loadList" />
        <el-button @click="loadList">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="teamCode" label="团队编码" min-width="140" />
        <el-table-column prop="model" label="模型" min-width="140">
          <template #default="{ row }">
            <span>{{ row.model || 'GLOBAL' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="credentialCode" label="凭证编码" min-width="140" />
        <el-table-column label="启用状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑策略' : '新建策略'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
        <el-form-item label="团队编码" prop="teamCode">
          <el-select v-model="form.teamCode" placeholder="选择团队" style="width: 100%" @focus="loadTeams">
            <el-option v-for="t in teamOptions" :key="t.teamCode" :label="`${t.teamCode} - ${t.teamName}`" :value="t.teamCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型名称" prop="model">
          <el-input v-model="form.model" placeholder="留空表示全局策略 (GLOBAL)" clearable />
        </el-form-item>
        <el-form-item label="凭证编码" prop="credentialCode">
          <el-select v-model="form.credentialCode" placeholder="选择凭证" style="width: 100%" @focus="loadCredentials">
            <el-option v-for="c in credentialOptions" :key="c.credentialCode" :label="`${c.credentialCode} (${c.providerCode})`" :value="c.credentialCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选备注信息" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  listModelPolicies,
  createModelPolicy,
  updateModelPolicy,
  deleteModelPolicy,
  listPolicyCredentials,
  type TeamModelPolicy
} from '@/api/model-policy'
import { listTeams, type Team } from '@/api/team'

const loading = ref(false)
const list = ref<TeamModelPolicy[]>([])
const total = ref(0)
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const teamCodes = ref<string[]>([])
const teamOptions = ref<Team[]>([])
const credentialOptions = ref<any[]>([])

const query = reactive({ page: 1, size: 20, teamCode: '', model: '', keyword: '' })

const form = reactive<TeamModelPolicy & { id?: number }>({
  id: undefined,
  teamCode: '',
  model: '',
  credentialCode: '',
  enabled: true,
  remark: '',
  createdAt: undefined
})

const rules: FormRules = {
  teamCode: [{ required: true, message: '请选择团队', trigger: 'change' }],
  credentialCode: [{ required: true, message: '请选择凭证', trigger: 'change' }]
}

async function loadList() {
  loading.value = true
  try {
    const res = await listModelPolicies(query)
    list.value = res.records
    total.value = res.total
    // 收集所有团队编码用于筛选
    const codes = new Set(list.value.map(item => item.teamCode))
    teamCodes.value = Array.from(codes)
  } finally {
    loading.value = false
  }
}

async function loadTeams() {
  if (teamOptions.value.length > 0) return
  try {
    const res = await listTeams({ page: 1, size: 100, teamType: '', keyword: '' })
    teamOptions.value = res.records
  } catch (e) {
    console.error('加载团队列表失败', e)
  }
}

async function loadCredentials() {
  if (credentialOptions.value.length > 0) return
  try {
    const res = await listPolicyCredentials(form.teamCode || undefined)
    credentialOptions.value = res
  } catch (e) {
    console.error('加载凭证列表失败', e)
  }
}

function openDialog(row?: TeamModelPolicy) {
  Object.assign(form, {
    id: undefined,
    teamCode: '',
    model: '',
    credentialCode: '',
    enabled: true,
    remark: ''
  })
  if (row) {
    Object.assign(form, { ...row, id: row.id })
  }
  // 重置下拉选项以便重新加载
  credentialOptions.value = []
  dialogVisible.value = true
}

async function handleSave() {
  await formRef.value?.validate()
  const payload = {
    teamCode: form.teamCode,
    model: form.model || null,
    credentialCode: form.credentialCode,
    enabled: form.enabled,
    remark: form.remark || undefined
  }
  if (form.id) {
    await updateModelPolicy(form.id, payload)
    ElMessage.success('更新成功')
  } else {
    await createModelPolicy(payload)
    ElMessage.success('创建成功')
  }
  dialogVisible.value = false
  loadList()
}

async function handleDelete(row: TeamModelPolicy) {
  await ElMessageBox.confirm(`确认删除团队 ${row.teamCode} 的模型策略？`, '提示', { type: 'warning' })
  await deleteModelPolicy(row.id!)
  ElMessage.success('删除成功')
  loadList()
}

onMounted(() => {
  loadList()
})
</script>

<style scoped lang="scss">
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .header-left {
    display: flex;
    align-items: center;
  }

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

.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}

.pagination {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
