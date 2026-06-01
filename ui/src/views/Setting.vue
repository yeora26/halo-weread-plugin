<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

interface ConfigResponse {
  cookieCloudUrl?: string
  cookieCloudUuid?: string
  cookieCloudPassword?: string
  cookieValid?: string
  cookieUserName?: string
  cookieLastRefreshTime?: string
  autoRefreshCookie?: string
  wereadApiKey?: string
}

const cookieCloudUrl = ref('')
const cookieCloudUuid = ref('')
const cookieCloudPassword = ref('')
const wereadApiKey = ref('')
const autoRefreshCookie = ref(false)
const cookieValid = ref(false)
const cookieUserName = ref('')
const cookieLastRefreshTime = ref('')

const cloudModalOpen = ref(false)
const isFetchingCloud = ref(false)
const isSyncing = ref(false)
const statusMessage = ref('')
const syncMessage = ref('')

const formatTime = (value: string) => {
  const timestamp = Number(value)
  if (!timestamp) return '暂无'
  const date = new Date(timestamp)
  return `${date.getFullYear()}/${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`
}

const cookieStatusText = computed(() => {
  return cookieValid.value
    ? `Cookie 有效，上次刷新时间：${formatTime(cookieLastRefreshTime.value)}`
    : 'Cookie 无效或未同步，请刷新 CookieCloud'
})

const maskedApiKey = computed(() => {
  if (!wereadApiKey.value) return ''
  if (wereadApiKey.value.length <= 8) return '********'
  return `${wereadApiKey.value.slice(0, 4)}${'*'.repeat(20)}${wereadApiKey.value.slice(-4)}`
})

const showStatus = (message: string) => {
  statusMessage.value = message
  setTimeout(() => {
    statusMessage.value = ''
  }, 5000)
}

const fetchConfig = async () => {
  try {
    const res = await fetch('/api/admin/halo-weread-plugin/cookie')
    if (!res.ok) return
    const data = await res.json() as ConfigResponse
    cookieCloudUrl.value = data.cookieCloudUrl || ''
    cookieCloudUuid.value = data.cookieCloudUuid || ''
    cookieCloudPassword.value = data.cookieCloudPassword || ''
    wereadApiKey.value = data.wereadApiKey || ''
    autoRefreshCookie.value = data.autoRefreshCookie === 'true'
    cookieValid.value = data.cookieValid === 'true'
    cookieUserName.value = data.cookieUserName || ''
    cookieLastRefreshTime.value = data.cookieLastRefreshTime || ''
  } catch (error) {
    showStatus('获取配置失败')
  }
}

const saveConfig = async (message = '配置已保存') => {
  try {
    const res = await fetch('/api/admin/halo-weread-plugin/cookie', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        cookieCloudUrl: cookieCloudUrl.value,
        cookieCloudUuid: cookieCloudUuid.value,
        cookieCloudPassword: cookieCloudPassword.value,
        wereadApiKey: wereadApiKey.value,
        autoRefreshCookie: String(autoRefreshCookie.value),
        userAgent: ''
      })
    })
    showStatus(res.ok ? message : '保存失败')
  } catch (error) {
    showStatus('保存异常')
  }
}

const fetchFromCookieCloud = async () => {
  if (!cookieCloudUrl.value || !cookieCloudUuid.value || !cookieCloudPassword.value) {
    showStatus('请填写完整的 CookieCloud 配置')
    cloudModalOpen.value = true
    return
  }
  isFetchingCloud.value = true
  showStatus('正在刷新 Cookie...')
  try {
    const res = await fetch('/api/admin/halo-weread-plugin/cookie-cloud/sync', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: cookieCloudUrl.value,
        uuid: cookieCloudUuid.value,
        password: cookieCloudPassword.value,
        userAgent: ''
      })
    })
    if (res.ok) {
      showStatus('Cookie 刷新成功')
      await fetchConfig()
      cloudModalOpen.value = false
    } else {
      const data = await res.json().catch(() => ({ message: '拉取失败，请检查配置' }))
      showStatus(data.message || '拉取失败，请检查配置')
    }
  } catch (error) {
    showStatus('通讯异常')
  } finally {
    isFetchingCloud.value = false
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
      const data = await res.json().catch(() => ({ message: '同步请求失败' }))
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
  <div class="settings-page">
    <section class="login-section">
      <div class="section-title-row">
        <h2>登录设置</h2>
      </div>

      <div class="dark-card account-card">
        <div class="account-text">
          微信读书{{ cookieValid ? '已登录' : '未登录' }}，用户名：
          <strong>{{ cookieUserName || '未知' }}</strong>
        </div>
        <button class="icon-btn" title="CookieCloud 配置" @click="cloudModalOpen = true">
          <span></span>
          <span></span>
        </button>
      </div>

      <div class="dark-card status-card">
        <div>
          <h3>Cookie 状态</h3>
          <p :class="{ ok: cookieValid, bad: !cookieValid }">
            <span class="status-mark">{{ cookieValid ? '✓' : '!' }}</span>
            {{ cookieStatusText }}
          </p>
        </div>
        <button class="primary-action" :disabled="isFetchingCloud" @click="fetchFromCookieCloud">
          {{ isFetchingCloud ? '刷新中...' : '立即刷新 Cookie' }}
        </button>
      </div>

      <div class="dark-card inline-setting">
        <div>
          <h3>自动刷新 Cookie</h3>
          <p>启动 Halo 后自动刷新 CookieCloud 凭证</p>
        </div>
        <label class="switch">
          <input v-model="autoRefreshCookie" type="checkbox" @change="saveConfig('自动刷新设置已保存')" />
          <span></span>
        </label>
      </div>

      <div class="dark-card sync-card">
        <div>
          <h3>数据同步</h3>
          <p>手动同步书籍、划线、想法和阅读进度</p>
          <p v-if="syncMessage" class="inline-message">{{ syncMessage }}</p>
        </div>
        <button class="secondary-action" :disabled="isSyncing" @click="triggerSync">
          {{ isSyncing ? '同步中...' : '立即同步' }}
        </button>
      </div>

      <div class="dark-card api-card">
        <div class="api-copy">
          <h3>微信读书 API Key</h3>
          <p>用于调用阅读统计等高级接口；划线和想法同步仍优先使用 Cookie。</p>
        </div>
        <div class="api-field">
          <input
            v-model="wereadApiKey"
            class="dark-input"
            type="password"
            placeholder="wrk-xxxxxxxx"
            autocomplete="off"
            @change="saveConfig('微信读书 API Key 已保存')"
          />
          <div v-if="maskedApiKey" class="masked-key">{{ maskedApiKey }}</div>
        </div>
      </div>
    </section>
    <p v-if="statusMessage" class="status-toast">{{ statusMessage }}</p>

    <Transition name="modal-fade">
      <div v-if="cloudModalOpen" class="modal-mask" @click.self="cloudModalOpen = false">
        <div class="cloud-modal">
          <button class="modal-close" @click="cloudModalOpen = false">×</button>
          <h2>CookieCloud 配置</h2>
          <div class="modal-divider"></div>

          <label class="modal-row">
            <span>服务器地址</span>
            <input v-model="cookieCloudUrl" class="dark-input" placeholder="https://ccc.ft07.com" />
          </label>
          <label class="modal-row">
            <span>用户KEY</span>
            <input v-model="cookieCloudUuid" class="dark-input" placeholder="CookieCloud UUID" />
          </label>
          <label class="modal-row">
            <span>端对端加密密码</span>
            <input v-model="cookieCloudPassword" class="dark-input" type="password" placeholder="端对端密码" />
          </label>

          <div class="modal-actions">
            <button class="primary-action" @click="saveConfig('CookieCloud 配置已保存')">保存</button>
            <button class="primary-action" :disabled="isFetchingCloud" @click="fetchFromCookieCloud">
              {{ isFetchingCloud ? '刷新中...' : '确定' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.settings-page {
  min-height: 100%;
  padding: 16px 22px;
  color: #172033;
  background: #f6f8fb;
  box-sizing: border-box;
}

.login-section {
  max-width: 1180px;
  margin: 0 auto;
}

.section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
}

h2 {
  margin: 0;
  color: #111827;
  font-size: 0.98rem;
  font-weight: 800;
  letter-spacing: 0;
}

.dark-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  padding: 11px 14px;
  border: 1px solid #e4e9f1;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.05);
}

.account-card {
  min-height: 42px;
}

.account-text {
  color: #172033;
  font-size: 0.72rem;
  font-weight: 700;
}

.account-text strong {
  color: #16794c;
  font-weight: 900;
}

.icon-btn {
  width: 25px;
  height: 22px;
  border: 1px solid #d7dee8;
  border-radius: 6px;
  background: #f8fafc;
  box-shadow: none;
  cursor: pointer;
  position: relative;
}

.icon-btn span {
  position: absolute;
  left: 7px;
  width: 11px;
  height: 1px;
  border-radius: 999px;
  background: #334155;
}

.icon-btn span:first-child {
  top: 7px;
}

.icon-btn span:last-child {
  bottom: 7px;
}

.icon-btn span::before {
  content: "";
  position: absolute;
  top: -2px;
  width: 3px;
  height: 3px;
  border: 1px solid #334155;
  border-radius: 50%;
  background: #f8fafc;
}

.icon-btn span:first-child::before {
  left: -2px;
}

.icon-btn span:last-child::before {
  right: -2px;
}

.status-card h3,
.inline-setting h3,
.sync-card h3,
.api-card h3 {
  margin: 0;
  color: #111827;
  font-size: 0.74rem;
  font-weight: 760;
}

.status-card p,
.inline-setting p,
.sync-card p,
.api-card p {
  margin: 3px 0 0;
  color: #64748b;
  font-size: 0.58rem;
  line-height: 1.45;
  font-weight: 600;
}

.status-card p.ok {
  color: #166534;
}

.status-card p.bad {
  color: #b91c1c;
}

.status-mark {
  display: inline-flex;
  width: 12px;
  height: 12px;
  align-items: center;
  justify-content: center;
  margin-right: 4px;
  border-radius: 3px;
  color: #fff;
  background: #22c55e;
  font-size: 0.52rem;
  font-weight: 900;
}

.bad .status-mark {
  background: #ef4444;
}

.primary-action,
.secondary-action {
  border: 1px solid #cfd9e6;
  border-radius: 7px;
  box-shadow: none;
  cursor: pointer;
  font-size: 0.62rem;
  font-weight: 760;
}

.primary-action {
  padding: 6px 10px;
  color: #fff;
  background: #2563eb;
}

.secondary-action {
  padding: 6px 10px;
  color: #172033;
  background: #eef4ff;
}

.primary-action:disabled,
.secondary-action:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.switch {
  position: relative;
  width: 32px;
  height: 18px;
  flex-shrink: 0;
}

.switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.switch span {
  position: absolute;
  inset: 0;
  cursor: pointer;
  border-radius: 999px;
  background: #e2e8f0;
  box-shadow: inset 0 0 0 2px #cbd5e1;
  transition: 0.2s;
}

.switch span::before {
  content: "";
  position: absolute;
  width: 14px;
  height: 14px;
  top: 2px;
  left: 2px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 3px 8px rgba(15, 23, 42, 0.16);
  transition: 0.2s;
}

.switch input:checked + span {
  background: #2563eb;
}

.switch input:checked + span::before {
  transform: translateX(14px);
}

.dark-input {
  width: 100%;
  padding: 7px 9px;
  border: 1px solid #d7dee8;
  border-radius: 8px;
  color: #172033;
  background: #fff;
  font-size: 0.64rem;
  font-weight: 650;
  box-sizing: border-box;
}

.dark-input:focus {
  outline: none;
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.14);
}

.inline-message {
  color: #2563eb !important;
}

.api-card {
  align-items: flex-start;
}

.api-copy {
  max-width: 440px;
}

.api-field {
  width: min(600px, 52vw);
}

.masked-key {
  margin-top: 6px;
  color: #64748b;
  font-size: 0.62rem;
  text-align: right;
}

.status-toast {
  position: fixed;
  left: 50%;
  bottom: 22px;
  z-index: 20;
  transform: translateX(-50%);
  margin: 0;
  padding: 7px 12px;
  border-radius: 999px;
  color: #172033;
  background: #fff;
  border: 1px solid #d7dee8;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.14);
  font-weight: 800;
}

.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.28);
}

.cloud-modal {
  position: relative;
  width: min(900px, calc(100vw - 36px));
  padding: 16px 16px 14px;
  border: 1px solid #e4e9f1;
  border-radius: 12px;
  color: #172033;
  background: #fff;
  box-shadow: 0 24px 80px rgba(15, 23, 42, 0.18);
}

.cloud-modal h2 {
  font-size: 0.82rem;
}

.modal-close {
  position: absolute;
  top: 8px;
  right: 10px;
  border: none;
  color: #64748b;
  background: transparent;
  cursor: pointer;
  font-size: 0.98rem;
  line-height: 1;
}

.modal-divider {
  height: 1px;
  margin: 12px 0 8px;
  background: #e4e9f1;
}

.modal-row {
  display: grid;
  grid-template-columns: minmax(140px, 0.7fr) minmax(300px, 1fr);
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid #e4e9f1;
}

.modal-row span {
  color: #172033;
  font-size: 0.66rem;
  font-weight: 700;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.2s;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}

@media (max-width: 820px) {
  .settings-page {
    padding: 14px 12px;
  }

  .section-title-row,
  .dark-card,
  .api-card {
    flex-direction: column;
    align-items: stretch;
  }

  .api-field {
    width: 100%;
    min-width: 0;
  }

  .modal-row {
    grid-template-columns: 1fr;
    gap: 6px;
  }
}
</style>
