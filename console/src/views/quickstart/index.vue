<template>
  <div class="page">
    <el-card>
      <template #header>
        <div class="header">
          <span class="title">Quick Start</span>
          <span class="desc">快速接入 Cursor / DeepSeek Harness / OpenAI SDK</span>
        </div>
      </template>

      <el-steps :active="2" align-center finish-status="success">
        <el-step title="选择 API Key" description="选择已有 Key 或去创建" />
        <el-step title="获取 Gateway URL" description="复制网关地址" />
        <el-step title="客户端配置" description="按示例粘贴配置" />
      </el-steps>

      <!-- 步骤 1：选择 API Key -->
      <div class="section">
        <div class="section-title">步骤 1：选择 API Key</div>
        <div class="section-body">
          <el-select v-model="selectedKey" placeholder="选择要接入的 API Key" filterable style="width: 420px" @change="renderSnippets">
            <el-option v-for="k in apiKeys" :key="k.accessKey" :label="`${k.keyName || k.accessKey}（${k.accessKey}）`" :value="k.accessKey" />
          </el-select>
          <el-button link type="primary" @click="goCreate">
            去创建
            <el-icon><ArrowRight /></el-icon>
          </el-button>
          <div v-if="apiKeys.length === 0" class="empty-tip">暂无可用 API Key，请先创建后再接入。</div>
        </div>
      </div>

      <!-- 步骤 2：Gateway URL -->
      <div class="section">
        <div class="section-title">步骤 2：Gateway URL</div>
        <div class="section-body">
          <template v-if="gatewayUrl">
            <el-input :model-value="gatewayUrl" readonly style="max-width: 520px">
              <template #append>
                <el-button @click="copy(gatewayUrl)">复制</el-button>
              </template>
            </el-input>
            <div class="url-note">所有客户端 Base URL 均为该地址，仅需替换 API Key。</div>
          </template>
          <el-alert v-else type="warning" :closable="false" show-icon title="Gateway Public URL 未配置" description="请联系 ADMIN 在「Settings 系统设置」中配置对外网关地址。" />
        </div>
      </div>

      <!-- 步骤 3：客户端配置示例 -->
      <div class="section">
        <div class="section-title">步骤 3：客户端配置示例</div>
        <el-tabs v-model="activeClient">
          <el-tab-pane label="Cursor" name="cursor">
            <el-alert type="info" :closable="false" show-icon title="Cursor 配置方式" description="Settings → Models → OpenAI API Key 中填入 TokenLimit 的 AccessKey；Base URL 填网关地址。" />
            <pre class="code-block">{{ snippetCursor }}</pre>
          </el-tab-pane>
          <el-tab-pane label="DeepSeek Harness" name="harness">
            <el-alert type="info" :closable="false" show-icon title="DeepSeek Harness 接入" description="将 Base URL 指向 TokenLimit 网关，模型名保持 deepseek-chat 等，由网关按 Team Model Policy 转发。" />
            <pre class="code-block">{{ snippetHarness }}</pre>
          </el-tab-pane>
          <el-tab-pane label="OpenAI SDK" name="sdk">
            <el-alert type="info" :closable="false" show-icon title="Python / Node SDK" description="使用官方 openai SDK，仅替换 base_url 与 api_key。" />
            <pre class="code-block">{{ snippetSdk }}</pre>
          </el-tab-pane>
          <el-tab-pane label="cURL" name="curl">
            <el-alert type="info" :closable="false" show-icon title="命令行快速验证" description="通过 cURL 发起一次对话请求，验证网关、配额与转发链路。" />
            <pre class="code-block">{{ snippetCurl }}</pre>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import { listMyApiKeys } from '@/api/my'
import { getSettings } from '@/api/settings'
import type { ApiKey } from '@/api/apikey'
import { useUserStore } from '@/stores/user'
import { ROLES } from '@/router'

const router = useRouter()
const userStore = useUserStore()
const apiKeys = ref<ApiKey[]>([])
const selectedKey = ref('')
const gatewayUrl = ref('')
const activeClient = ref('cursor')

const snippetCursor = computed(() => {
  const base = gatewayUrl.value || '<GATEWAY_URL>'
  return `# Cursor 设置（Settings → Models）
# 选择 OpenAI API Key，填入：
API Key:    ${selectedKey.value || '<TOKENLIMIT_ACCESS_KEY>'}
Base URL:   ${base}/v1
Model:      gpt-4o-mini（需在 Team Model Policy 中放行）`
})

const snippetHarness = computed(() => {
  const base = gatewayUrl.value || '<GATEWAY_URL>'
  return `# DeepSeek Harness 配置
BASE_URL = "${base}/v1"
API_KEY  = "${selectedKey.value || '<TOKENLIMIT_ACCESS_KEY>'}"
MODEL    = deepseek-chat`
})

const snippetSdk = computed(() => {
  const base = gatewayUrl.value || '<GATEWAY_URL>'
  return `from openai import OpenAI

client = OpenAI(
    base_url="${base}/v1",
    api_key="${selectedKey.value || '<TOKENLIMIT_ACCESS_KEY>'}",  # TokenLimit AccessKey
)

resp = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[{"role": "user", "content": "Hello"}],
    stream=True,
)
for chunk in resp:
    print(chunk)`
})

const snippetCurl = computed(() => {
  const base = gatewayUrl.value || '<GATEWAY_URL>'
  return `curl ${base}/v1/chat/completions \\
  -H "Authorization: Bearer ${selectedKey.value || '<TOKENLIMIT_ACCESS_KEY>'}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "gpt-4o-mini",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true
  }'`
})

async function load() {
  const [keys, settings] = await Promise.all([listMyApiKeys({ page: 1, size: 100 }), getSettings()])
  apiKeys.value = keys.records
  if (apiKeys.value.length > 0) selectedKey.value = apiKeys.value[0].accessKey || ''
  gatewayUrl.value = settings.gateway_public_url || ''
}

function renderSnippets() {
  // 触发 computed 重新计算（无副作用）
}

function goCreate() {
  const target = userStore.role === ROLES.USER ? '/my/api-keys' : '/api-keys'
  router.push(target)
}

function copy(text: string) {
  navigator.clipboard?.writeText(text).then(() => ElMessage.success('已复制'))
}

onMounted(() => {
  load()
})
</script>

<style scoped lang="scss">
.header {
  display: flex;
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

.section {
  margin-top: 28px;

  .section-title {
    font-size: 14px;
    font-weight: 600;
    margin-bottom: 12px;
  }

  .section-body {
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }

  .empty-tip {
    color: #e6a23c;
    font-size: 13px;
  }

  .url-note {
    color: #909399;
    font-size: 13px;
  }
}

.code-block {
  background: #1e1e1e;
  color: #d4d4d4;
  border-radius: 8px;
  padding: 16px;
  font-size: 13px;
  line-height: 1.7;
  overflow-x: auto;
  margin-top: 12px;
}
</style>
