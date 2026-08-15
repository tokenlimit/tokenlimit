<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <div class="header-left">
            <span class="title">Team 团队</span>
            <span class="desc">团队 / 部门 / 应用 / 项目 / 客户 / 成本中心管理</span>
          </div>
          <el-button type="primary" @click="openDialog()">新建团队</el-button>
        </div>
      </template>

      <div class="toolbar">
        <el-select v-model="query.teamType" placeholder="团队类型" clearable style="width: 160px" @change="loadList">
          <el-option v-for="t in teamTypes" :key="t" :label="t" :value="t" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="搜索编码或名称" clearable style="width: 220px" @keyup.enter="loadList" @clear="loadList" />
        <el-button @click="loadList">查询</el-button>
      </div>

      <el-table :data="list" v-loading="loading">
        <el-table-column prop="teamCode" label="团队编码" min-width="140" />
        <el-table-column prop="teamName" label="名称" min-width="140" />
        <el-table-column prop="teamType" label="类型" width="130">
          <template #default="{ row }">
            <el-tag>{{ row.teamType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="180" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑团队' : '新建团队'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="团队编码" prop="teamCode">
          <el-input v-model="form.teamCode" :disabled="!!form.id" placeholder="如：team-rd" />
        </el-form-item>
        <el-form-item label="名称" prop="teamName">
          <el-input v-model="form.teamName" placeholder="如：研发中心" />
        </el-form-item>
        <el-form-item label="团队类型" prop="teamType">
          <el-select v-model="form.teamType" placeholder="选择类型" style="width: 100%">
            <el-option v-for="t in teamTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" active-value="ACTIVE" inactive-value="INACTIVE" active-text="启用" inactive-text="停用" />
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
import { createTeam, deleteTeam, listTeams, updateTeam, type Team } from '@/api/team'
import { getMetaAll } from '@/api/meta'

const loading = ref(false)
const list = ref<Team[]>([])
const total = ref(0)
const teamTypes = ref<string[]>(['TEAM', 'DEPARTMENT', 'APPLICATION', 'PROJECT', 'CUSTOMER', 'COST_CENTER'])
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()

const query = reactive({ page: 1, size: 10, teamType: '', keyword: '' })

const form = reactive<Team>({
  id: undefined,
  teamCode: '',
  teamName: '',
  teamType: 'TEAM',
  description: '',
  status: 'ACTIVE'
})

const rules: FormRules = {
  teamCode: [{ required: true, message: '请输入团队编码', trigger: 'blur' }],
  teamName: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  teamType: [{ required: true, message: '请选择团队类型', trigger: 'change' }]
}

async function loadMeta() {
  try {
    const meta = await getMetaAll()
    teamTypes.value = meta.teamTypes
  } catch {
    // 忽略元数据加载失败
  }
}

async function loadList() {
  loading.value = true
  try {
    const res = await listTeams(query)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function openDialog(row?: Team) {
  Object.assign(form, { id: undefined, teamCode: '', teamName: '', teamType: 'TEAM', description: '', status: 'ACTIVE' })
  if (row) Object.assign(form, row)
  dialogVisible.value = true
}

async function handleSave() {
  await formRef.value?.validate()
  if (form.id) {
    await updateTeam(form.id, form)
    ElMessage.success('更新成功')
  } else {
    await createTeam(form)
    ElMessage.success('创建成功')
  }
  dialogVisible.value = false
  loadList()
}

async function handleDelete(row: Team) {
  await ElMessageBox.confirm(`确认删除团队 ${row.teamCode}？`, '提示', { type: 'warning' })
  await deleteTeam(row.id!)
  ElMessage.success('删除成功')
  loadList()
}

onMounted(() => {
  loadMeta()
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
