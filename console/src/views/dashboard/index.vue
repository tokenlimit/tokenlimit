<template>
  <div class="page">
    <el-row :gutter="16" class="stat-row">
      <el-col :span="3" v-for="item in stats" :key="item.label">
        <el-card>
          <div class="stat">
            <div class="value">{{ item.value }}</div>
            <div class="label">{{ item.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :span="14">
        <el-card>
          <template #header>近 7 天 Token 消耗趋势</template>
          <div class="trend" v-loading="trendLoading">
            <div class="bar-chart">
              <div v-for="item in trend" :key="item.date" class="bar-item">
                <div class="bar-value">{{ formatNum(item.value) }}</div>
                <div class="bar" :style="{ height: barHeight(item.value) }"></div>
                <div class="bar-label">{{ item.date.slice(5) }}</div>
              </div>
            </div>
            <el-empty v-if="!trendLoading && trend.length === 0" description="暂无数据" />
          </div>
        </el-card>
      </el-col>

      <el-col :span="10">
        <el-card>
          <template #header>高消耗团队 Top 5</template>
          <el-table :data="topTeams" v-loading="topLoading" size="small">
            <el-table-column prop="teamName" label="团队名称" min-width="150" />
            <el-table-column prop="teamCode" label="TeamCode" min-width="120" show-overflow-tooltip />
            <el-table-column label="Tokens" width="110">
              <template #default="{ row }">{{ Number(row.tokens).toLocaleString() }}</template>
            </el-table-column>
            <el-table-column label="调用次数" width="100">
              <template #default="{ row }">{{ Number(row.calls).toLocaleString() }}</template>
            </el-table-column>
            <el-table-column label="费用(¥)" width="100">
              <template #default="{ row }">{{ Number(row.cost).toFixed(2) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!topLoading && topTeams.length === 0" description="暂无数据" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getDashboardStats, getTopTeams, getDashboardTrend, type TopTeam, type TrendPoint } from '@/api/dashboard'

const stats = ref<{ label: string; value: number | string }[]>([
  { label: '团队总数', value: 0 },
  { label: '配额规则数', value: 0 },
  { label: '用户总数', value: 0 },
  { label: 'API Key 数', value: 0 },
  { label: '今日 Token', value: 0 },
  { label: '今日调用', value: 0 },
  { label: '今日费用(¥)', value: 0 }
])
const trend = ref<TrendPoint[]>([])
const topTeams = ref<TopTeam[]>([])
const trendLoading = ref(false)
const topLoading = ref(false)

const maxTrend = computed(() => Math.max(...trend.value.map((t) => t.value), 1))

function formatNum(n: number) {
  return n >= 10000 ? `${(n / 10000).toFixed(1)}w` : String(n)
}

function barHeight(value: number) {
  const ratio = value / maxTrend.value
  return `${Math.max(4, Math.round(ratio * 160))}px`
}

async function loadStats() {
  try {
    const data = await getDashboardStats()
    stats.value = [
      { label: '团队总数', value: data.totalTeams },
      { label: '配额规则数', value: data.totalQuotas },
      { label: '用户总数', value: data.totalUsers },
      { label: 'API Key 数', value: data.totalApiKeys },
      { label: '今日 Token', value: formatNum(data.todayTokens) },
      { label: '今日调用', value: data.todayCalls },
      { label: '今日费用(¥)', value: Number(data.todayCost).toFixed(2) }
    ]
  } catch {
    // 接口未就绪时使用默认值
  }
}

async function loadTrend() {
  trendLoading.value = true
  try {
    trend.value = await getDashboardTrend(7)
  } finally {
    trendLoading.value = false
  }
}

async function loadTopTeams() {
  topLoading.value = true
  try {
    topTeams.value = await getTopTeams(5)
  } finally {
    topLoading.value = false
  }
}

onMounted(() => {
  loadStats()
  loadTrend()
  loadTopTeams()
})
</script>

<style scoped lang="scss">
.stat-row {
  margin-bottom: 16px;

  .stat {
    text-align: center;
    padding: 8px 0;

    .value {
      font-size: 26px;
      font-weight: 600;
      color: #303133;
    }

    .label {
      margin-top: 8px;
      color: #909399;
    }
  }
}

.trend {
  min-height: 240px;

  .bar-chart {
    height: 220px;
    display: flex;
    align-items: flex-end;
    gap: 14px;
    padding-top: 10px;

    .bar-item {
      flex: 1;
      display: flex;
      flex-direction: column;
      justify-content: flex-end;
      align-items: center;
      gap: 6px;

      .bar-value {
        font-size: 12px;
        color: #909399;
      }

      .bar {
        width: 100%;
        max-width: 46px;
        background: linear-gradient(180deg, #409eff, #79bbff);
        border-radius: 6px 6px 0 0;
      }

      .bar-label {
        font-size: 12px;
        color: #909399;
      }
    }
  }
}
</style>
