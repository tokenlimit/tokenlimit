<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="title">Token Limit 管理控制台</h2>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleLogin">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">
            登录
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 首次登录强制改密 -->
    <el-dialog v-model="changeVisible" title="首次登录，请修改密码" width="420px" :close-on-click-modal="false" :show-close="false">
      <el-form ref="changeFormRef" :model="changeForm" :rules="changeRules" label-position="top">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="changeForm.oldPassword" type="password" placeholder="请输入原密码" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="changeForm.newPassword" type="password" placeholder="8-32 位，包含字母和数字" show-password />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="changeForm.confirmPassword" type="password" placeholder="请再次输入新密码" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :loading="changing" @click="handleChangePassword">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { changePassword } from '@/api/auth'
import { ROLES } from '@/router'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  username: '',
  password: ''
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  await formRef.value?.validate()
  loading.value = true
  try {
    await userStore.login(form)
    ElMessage.success('登录成功')
    if (userStore.mustChangePassword) {
      changeForm.oldPassword = form.password
      changeVisible.value = true
      return
    }
    redirectAfterLogin()
  } finally {
    loading.value = false
  }
}

function redirectAfterLogin() {
  router.push(userStore.role === ROLES.USER ? '/my/overview' : '/dashboard')
}

// 首次登录强制改密
const changeVisible = ref(false)
const changing = ref(false)
const changeFormRef = ref<FormInstance>()
const changeForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const changeRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d)[\S]{8,32}$/,
      message: '8-32 位，需包含字母和数字',
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (value !== changeForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

async function handleChangePassword() {
  await changeFormRef.value?.validate()
  changing.value = true
  try {
    const result = await changePassword({
      oldPassword: changeForm.oldPassword,
      newPassword: changeForm.newPassword
    })
    // 改密成功：应用服务端签发的新 JWT（mustChangePassword=false），无需重新登录
    userStore.apply(result)
    ElMessage.success('密码修改成功')
    changeVisible.value = false
    redirectAfterLogin()
  } finally {
    changing.value = false
  }
}
</script>

<style scoped lang="scss">
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #304156 100%);
}

.login-card {
  width: 380px;
  padding: 20px 12px;

  .title {
    text-align: center;
    margin-bottom: 24px;
  }

  .login-btn {
    width: 100%;
  }
}
</style>
