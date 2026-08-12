<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Box, Refresh, Download, VideoPlay, Delete } from '@element-plus/icons-vue'
import { pluginApi, type PluginCatalogItem, type PluginInstall, type PluginRelease } from '../api'

const loading = ref(false)
const catalog = ref<PluginCatalogItem[]>([])
const installed = ref<PluginInstall[]>([])
const categoryLabels: Record<string, string> = {
  CODE: '代码解析', API: '接口解析', DDL: 'DDL 解析', CONFIG: '配置解析', DEPENDENCY: '依赖解析'
}
const categoryColors: Record<string, string> = {
  CODE: '#a8b4ff', API: '#38e1ff', DDL: '#34d399', CONFIG: '#fbbf24', DEPENDENCY: '#a78bfa'
}

const installedMap = ref<Record<string, PluginInstall>>({})

async function load() {
  loading.value = true
  try {
    const [cat, ins] = await Promise.all([pluginApi.catalog(), pluginApi.installed()])
    catalog.value = cat
    installed.value = ins
    installedMap.value = Object.fromEntries(ins.map((i) => [i.pluginId, i]))
  } catch {
    ElMessage.error('加载插件列表失败')
  }
  loading.value = false
}

// ===== 安装 =====
const installOpen = ref(false)
const installTarget = ref<PluginCatalogItem | null>(null)
const releases = ref<PluginRelease[]>([])
const selectedVersion = ref('')
async function openInstall(item: PluginCatalogItem) {
  installTarget.value = item
  releases.value = []
  selectedVersion.value = ''
  try { releases.value = await pluginApi.releases(item.pluginId) } catch {}
  installOpen.value = true
}
async function doInstall() {
  if (!selectedVersion.value) return ElMessage.warning('请选择版本')
  try {
    await pluginApi.install(installTarget.value!.pluginId, selectedVersion.value)
    ElMessage.success('插件已安装')
    installOpen.value = false
    load()
  } catch {
    ElMessage.error('安装失败')
  }
}

async function toggle(item: PluginInstall) {
  try {
    await pluginApi.toggle(item.pluginId, !item.enabled)
    load()
  } catch {
    ElMessage.error('操作失败')
  }
}

async function uninstall(item: PluginInstall) {
  await ElMessageBox.confirm(`确认卸载插件「${item.pluginId}」？`, '卸载确认', { type: 'warning' })
  try { await pluginApi.uninstall(item.pluginId); load() } catch { ElMessage.error('卸载失败') }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="left">
        <span class="et-tic"><el-icon><Box /></el-icon></span>
        <span class="tip">解析器插件市场 —— SPI 生态，无需改主代码即可扩展解析能力</span>
      </div>
      <div class="right">
        <button class="ops-btn" @click="load"><el-icon><Refresh /></el-icon> 刷新</button>
      </div>
    </div>

    <!-- 已安装 -->
    <div class="et-card">
      <div class="et-card-head">
        <span>已安装插件</span>
        <span class="count">{{ installed.length }} 个</span>
      </div>
      <div class="et-card-body no-padding">
        <el-table :data="installed" v-loading="loading" size="default" style="width: 100%">
          <el-table-column label="插件" min-width="220">
            <template #default="{ row }">
              <div class="plugin-name">
                <span class="p-avatar"><el-icon><Box /></el-icon></span>
                <div>
                  <div class="name">{{ row.name || row.pluginId }}</div>
                  <div class="id">{{ row.pluginId }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="分类" width="120">
            <template #default="{ row }">
              <span class="cat" :style="{ color: categoryColors[row.category], background: categoryColors[row.category] + '26' }">
                {{ categoryLabels[row.category] || row.category }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="110">
            <template #default="{ row }"><span class="ver">v{{ row.version }}</span></template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }"><el-switch v-model="row.enabled" @change="toggle(row)" /></template>
          </el-table-column>
          <el-table-column label="安装时间" width="170">
            <template #default="{ row }">{{ row.installedAt || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <div class="ops">
                <button class="ops-btn danger" @click="uninstall(row)"><el-icon><Delete /></el-icon> 卸载</button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 市场 -->
    <div class="market-grid">
      <div v-for="item in catalog" :key="item.pluginId" class="plugin-card">
        <div class="pc-top">
          <span class="pc-icon"><el-icon><Box /></el-icon></span>
          <span class="cat" :style="{ color: categoryColors[item.category], background: categoryColors[item.category] + '26' }">
            {{ categoryLabels[item.category] || item.category }}
          </span>
        </div>
        <div class="pc-name">{{ item.name }}</div>
        <div class="pc-id">{{ item.pluginId }}</div>
        <div class="pc-desc">{{ item.description || '暂无描述' }}</div>
        <div class="pc-meta">
          <span v-if="item.author">作者：{{ item.author }}</span>
          <span v-if="item.compatRange">兼容 {{ item.compatRange }}</span>
        </div>
        <div class="pc-actions">
          <button v-if="installedMap[item.pluginId]" class="ops-btn success" disabled>
            <el-icon><VideoPlay /></el-icon> 已安装
          </button>
          <button v-else class="ops-btn primary" @click="openInstall(item)">
            <el-icon><Download /></el-icon> 安装
          </button>
        </div>
      </div>
      <div v-if="!catalog.length && !loading" class="empty">
        <el-icon><Box /></el-icon>
        市场暂无插件，可通过插件发布流程上架
      </div>
    </div>

    <!-- 安装弹窗 -->
    <el-dialog v-model="installOpen" :title="`安装插件 · ${installTarget?.name}`" width="440px">
      <el-form label-width="80px">
        <el-form-item label="插件 ID"><span class="mono">{{ installTarget?.pluginId }}</span></el-form-item>
        <el-form-item label="选择版本" required>
          <el-select v-model="selectedVersion" placeholder="请选择版本" style="width: 100%">
            <el-option v-for="r in releases" :key="r.version" :label="`v${r.version}`" :value="r.version" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="installOpen = false">取消</el-button>
        <el-button type="primary" @click="doInstall">安装</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; margin-bottom: 16px; flex-wrap: wrap;
}
.page-toolbar .left { display: flex; align-items: center; gap: 10px; }
.page-toolbar .right { display: flex; align-items: center; gap: 10px; }
.tip { font-size: 13px; color: var(--et-text-secondary); }
.ops-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 14px; border-radius: 20px; border: 1px solid transparent;
  font-family: inherit; font-size: 13px; font-weight: 600; cursor: pointer; transition: all 0.18s;
}
.ops-btn.primary { background: rgba(109, 124, 255, 0.14); color: #a8b4ff; }
.ops-btn.primary:hover { background: rgba(109, 124, 255, 0.28); box-shadow: 0 0 12px rgba(109, 124, 255, 0.3); }
.ops-btn.danger { background: rgba(251, 113, 133, 0.14); color: #fb7185; }
.ops-btn.danger:hover { background: rgba(251, 113, 133, 0.28); box-shadow: 0 0 12px rgba(251, 113, 133, 0.3); }
.ops-btn.success { background: rgba(52, 211, 153, 0.14); color: #34d399; }
.ops-btn.success[disabled] { opacity: 0.6; cursor: not-allowed; }
.ops { display: flex; gap: 6px; }
.et-card-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16px 20px 12px; font-size: 13.5px; font-weight: 700;
}
.count { font-size: 11.5px; color: var(--et-text-muted); font-weight: 500; }
.plugin-name { display: flex; align-items: center; gap: 10px; }
.p-avatar {
  width: 34px; height: 34px; border-radius: 10px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, var(--et-grad-a), var(--et-grad-b));
  color: #fff;
}
.plugin-name .name { font-weight: 600; }
.plugin-name .id { font-size: 11px; color: var(--et-text-muted); font-family: ui-monospace, monospace; }
.cat { font-size: 11.5px; font-weight: 700; padding: 3px 10px; border-radius: 20px; }
.ver { font-family: ui-monospace, monospace; font-size: 12px; color: var(--et-text-secondary); }
.mono { font-family: ui-monospace, monospace; font-size: 12px; }

.market-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px; margin-top: 16px;
}
.plugin-card {
  background: var(--et-card-solid); border: 1px solid var(--et-hover-border);
  border-radius: 16px; padding: 18px; display: flex; flex-direction: column; gap: 8px;
  transition: transform 0.18s, box-shadow 0.18s, border-color 0.18s;
}
.plugin-card:hover {
  transform: translateY(-3px); border-color: var(--et-primary);
  box-shadow: 0 12px 30px rgba(2, 6, 23, 0.35);
}
.pc-top { display: flex; align-items: center; justify-content: space-between; }
.pc-icon {
  width: 40px; height: 40px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  background: rgba(109, 124, 255, 0.12); color: var(--et-primary-light);
}
.pc-name { font-size: 15px; font-weight: 700; }
.pc-id { font-size: 11px; color: var(--et-text-muted); font-family: ui-monospace, monospace; }
.pc-desc { font-size: 12.5px; color: var(--et-text-secondary); min-height: 34px; line-height: 1.5; }
.pc-meta { display: flex; flex-direction: column; gap: 2px; font-size: 11px; color: var(--et-text-muted); }
.pc-actions { margin-top: auto; padding-top: 8px; }
.empty {
  grid-column: 1 / -1; text-align: center; color: var(--et-text-muted);
  padding: 40px; font-size: 13px;
}
.empty .el-icon { vertical-align: middle; margin-right: 6px; }
</style>