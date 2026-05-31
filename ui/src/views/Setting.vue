<script setup lang="ts">
import { ref, onMounted } from 'vue'

const cookieCloudUrl = ref('')
const cookieCloudUuid = ref('')
const cookieCloudPassword = ref('')
const isFetchingCloud = ref(false)
const isSyncing = ref(false)
const cloudMessage = ref('')
const syncMessage = ref('')
const saveMessage = ref('')

const fetchConfig = async () => {
  try {
    const res = await fetch('/api/admin/halo-weread-plugin/cookie')
    if (res.ok) {
      const data = await res.json()
      cookieCloudUrl.value = data.cookieCloudUrl || ''
      cookieCloudUuid.value = data.cookieCloudUuid || ''
      cookieCloudPassword.value = data.cookieCloudPassword || ''
    }
  } catch (error) {
    console.error('获取配置失败', error)
  }
}

const saveConfig = async () => {
  saveMessage.value = ''
  try {
    const res = await fetch('/api/admin/halo-weread-plugin/cookie', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        cookieCloudUrl: cookieCloudUrl.value,
        cookieCloudUuid: cookieCloudUuid.value,
        cookieCloudPassword: cookieCloudPassword.value,
        userAgent: '',
      }),
    })
    saveMessage.value = res.ok ? '配置已保存' : '保存失败'
    setTimeout(() => (saveMessage.value = ''), 3000)
  } catch (error) {
    saveMessage.value = '保存异常'
  }
}

const fetchFromCookieCloud = async () => {
  if (!cookieCloudUrl.value || !cookieCloudUuid.value || !cookieCloudPassword.value) {
    cloudMessage.value = '请填写完整的 CookieCloud 配置'
    return
  }
  isFetchingCloud.value = true
  cloudMessage.value = '正在拉取...'
  try {
    const res = await fetch('/api/admin/halo-weread-plugin/cookie-cloud/sync', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: cookieCloudUrl.value,
        uuid: cookieCloudUuid.value,
        password: cookieCloudPassword.value,
        userAgent: '',
      }),
    })
    if (res.ok) {
      cloudMessage.value = '获取成功，已自动同步凭证'
    } else {
      cloudMessage.value = '拉取失败，请检查配置'
    }
  } catch (error) {
    cloudMessage.value = '通讯异常'
  } finally {
    isFetchingCloud.value = false
    setTimeout(() => (cloudMessage.value = ''), 5000)
  }
}

const triggerSync = async () => {
  isSyncing.value = true
  syncMessage.value = '正在同步微信读书数据...'
  try {
    const res = await fetch('/api/admin/halo-weread-plugin/sync', { method: 'POST' })
    if (res.ok) {
      syncMessage.value = '同步触发成功，请稍后在书籍管理中刷新查看'
    } else {
      const data = await res.json().catch(() => ({}))
      syncMessage.value = '同步请求失败: ' + (data.message || '错误码 ' + res.status)
    }
  } catch (error) {
    syncMessage.value = '网络异常: ' + (error instanceof Error ? error.message : String(error))
  } finally {
    isSyncing.value = false
    setTimeout(() => (syncMessage.value = ''), 8000)
  }
}

onMounted(() => {
  fetchConfig()
})
</script>

<template>
  <div class="st-container">
    <div class="st-grid">
      <!-- 主要配置 -->
      <div class="st-main">
        <div class="st-card">
          <div class="st-card-header">
            <h3 class="st-card-title">CookieCloud 自动同步</h3>
            <p class="st-card-desc">推荐方式：通过 CookieCloud 服务端自动同步微信读书凭证</p>
          </div>
          <div class="st-card-body">
            <div class="field">
              <label>API 地址</label>
              <input type="text" v-model="cookieCloudUrl" placeholder="https://..." />
            </div>
            <div class="field-row">
              <div class="field">
                <label>UUID</label>
                <input type="text" v-model="cookieCloudUuid" placeholder="你的 UUID" />
              </div>
              <div class="field">
                <label>加密密码</label>
                <input type="password" v-model="cookieCloudPassword" placeholder="端对端密码" />
              </div>
            </div>
            <div class="st-card-footer">
              <button @click="saveConfig" class="h-btn-primary">保存配置</button>
              <button
                :disabled="isFetchingCloud"
                @click="fetchFromCookieCloud"
                class="h-btn-accent"
              >
                {{ isFetchingCloud ? '拉取中...' : '立即从云端获取' }}
              </button>
              <span v-if="saveMessage" class="feedback-msg">{{ saveMessage }}</span>
            </div>
            <p v-if="cloudMessage" class="cloud-msg">{{ cloudMessage }}</p>
          </div>
        </div>
      </div>

      <!-- 立即同步 -->
      <div class="st-side">
        <div class="st-card">
          <div class="st-card-header">
            <h3 class="st-card-title">立即同步</h3>
            <p class="st-card-desc">手动启动全量数据同步流程</p>
          </div>
          <div class="st-card-body">
            <div class="sync-actions">
              <button :disabled="isSyncing" @click="triggerSync" class="h-btn-outline full-width">
                {{ isSyncing ? '同步进行中...' : '启动数据拉取' }}
              </button>
              <p v-if="syncMessage" class="sync-msg">{{ syncMessage }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.st-container {
  padding: 24px;
  animation: fadeIn 0.3s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.st-grid {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 20px;
}

/* Card Style */
.st-card {
  background: #fff;
  border: 1px solid #eef0f2;
  border-radius: 12px;
  margin-bottom: 20px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.02);
}
.st-card-header {
  padding: 20px;
  border-bottom: 1px solid #f8fafc;
}
.st-card-title {
  font-size: 1rem;
  font-weight: 700;
  margin: 0;
  color: #1a1c1e;
}
.st-card-desc {
  font-size: 0.8rem;
  color: #64748b;
  margin: 4px 0 0;
}
.st-card-body {
  padding: 20px;
}
.st-card-footer {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

/* Form Controls */
.field {
  margin-bottom: 16px;
}
.field-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}
.field label {
  display: block;
  font-size: 0.85rem;
  font-weight: 600;
  color: #334155;
  margin-bottom: 6px;
}
.field-help {
  margin: 6px 0 0;
  color: #64748b;
  font-size: 0.78rem;
  line-height: 1.5;
}
textarea, input {
  width: 100%;
  padding: 10px 12px;
  border: 1.5px solid #e2e8f0;
  border-radius: 8px;
  font-size: 0.9rem;
  background: #f8fafc;
  transition: all 0.2s;
  box-sizing: border-box;
}
textarea:focus, input:focus {
  outline: none;
  border-color: #3b82f6;
  background: #fff;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

/* Buttons */
.h-btn-primary {
  background: #1e293b;
  color: #fff;
  padding: 10px 24px;
  border-radius: 8px;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: background 0.2s;
}
.h-btn-primary:hover { background: #0f172a; }

.h-btn-outline {
  background: transparent;
  border: 1.5px solid #e2e8f0;
  color: #475569;
  padding: 10px 24px;
  border-radius: 8px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.h-btn-outline:hover { background: #f8fafc; border-color: #cbd5e1; }
.full-width { width: 100%; }

.h-btn-accent {
  background: #10b981;
  color: #fff;
  padding: 10px 20px;
  border-radius: 8px;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: background 0.2s;
}
.h-btn-accent:hover { background: #059669; }

/* Messages */
.feedback-msg {
  font-size: 0.85rem;
  margin-left: auto;
  font-weight: 500;
}
.sync-msg, .cloud-msg {
  font-size: 0.8rem;
  margin-top: 12px;
  padding: 8px 12px;
  background: #f1f5f9;
  border-radius: 6px;
  color: #475569;
}

/* 响应式样式 */
@media (max-width: 900px) {
  .st-grid {
    grid-template-columns: 1fr;
  }

  .st-side {
    order: -1; /* 让同步按钮在手机端排在配置前面，或者根据需要调整 */
  }
}

@media (max-width: 600px) {
  .st-container {
    padding: 16px;
  }

  .field-row {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .st-card-footer {
    flex-direction: column;
    align-items: stretch;
  }

  .h-btn-primary, .h-btn-accent {
    width: 100%;
  }

  .feedback-msg {
    margin-left: 0;
    text-align: center;
  }
}
</style>
