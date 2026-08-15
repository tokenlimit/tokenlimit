<template>
  <div class="page">
    <el-card style="max-width: 820px">
      <template #header>
        <div class="header">
          <span class="title">Settings 系统设置</span>
          <span class="desc">配置网关地址、预估安全系数等（PRD V4.0）</span>
        </div>
      </template>

      <el-form ref="formRef" :model="form" label-width="160px" v-loading="loading">
        <el-form-item label="Gateway Public URL">
          <el-input v-model="form.gateway_public_url" placeholder="https://gateway.example.com" />
          <div class="form-tip">对外网关地址，Quick Start 页面只读展示</div>
        </el-form-item>
        <el-form-item label="预估安全系数">
          <el-input-number v-model="safeFactor" :min="1" :max="3" :step="0.1" />
          <div class="form-tip">配额预估消耗 = 实际消耗 × 系数，默认 1.1</div>
        </el-form-item>
        <el-form-item label="默认模型">
          <el-select v-model="form.default_model" allow-create filterable style="width: 100%">
            <el-option v-for="m in models" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="预算告警阈值（%）">
          <el-input-number v-model="alertThreshold" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="审计日志保留时间">
          <el-select v-model="form.audit_retention">
            <el-option label="30 天" value="30" />
            <el-option label="90 天" value="90" />
            <el-option label="180 天" value="180" />
            <el-option label="365 天" value="365" />
          </el-select>
        </el-form-item>
        <el-form-item label="告警通知渠道">
          <el-select v-model="form.notify_channel">
            <el-option label="钉钉机器人" value="dingtalk" />
            <el-option label="飞书机器人" value="lark" />
            <el-option label="企业微信" value="wecom" />
            <el-option label="邮件" value="email" />
          </el-select>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">保存设置</el-button>
          <el-button @click="loadSettings">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getSettings, saveSettings, type SettingsMap } from '@/api/settings'

const loading = ref(false)
const saving = ref(false)
const models = ['gpt-4o', 'gpt-4o-mini', 'claude-sonnet', 'qwen-max', 'deepseek-chat']

const form = reactive<SettingsMap>({
  gateway_public_url: '',
  safe_factor: '1.1',
  default_model: 'gpt-4o-mini',
  alert_threshold: '80',
  audit_retention: '90',
  notify_channel: 'dingtalk'
})

const safeFactor = computed({
  get: () => Number(form.safe_factor || 1.1),
  set: (v: number | undefined) => {
    form.safe_factor = String(v ?? 1.1)
  }
})

const alertThreshold = computed({
  get: () => Number(form.alert_threshold || 0),
  set: (v: number | undefined) => {
    form.alert_threshold = String(v ?? 0)
  }
})

async function loadSettings() {
  loading.value = true
  try {
    const data = await getSettings()
    Object.assign(form, data)
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  saving.value = true
  try {
    await saveSettings({ ...form })
    ElMessage.success('保存成功')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadSettings()
})
</script>

<style scoped lang="scss">
.header {
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

.form-tip {
  width: 100%;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
</style>
