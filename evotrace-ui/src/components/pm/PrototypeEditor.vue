<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import { pmApi, type PrototypeElement, type PrototypePage } from '../../api'
import { buildStandaloneHtml, downloadPrototypeHtml } from '../../utils/prototypeExport'

const props = defineProps<{
  modelValue: boolean
  projectKey: string
  requirementId: number
  requirementTitle: string
  aiUsable: boolean
}>()

const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

// ---- 画布 ----
const canvasRef = ref<HTMLElement | null>(null)
const scale = ref(0.8)
const pages = ref<PrototypePage[]>([])
const activePageId = ref<string>('')
const selectedElId = ref<string>('')
const dirty = ref(false)
const loading = ref(false)
const previewMode = ref(false)
const generating = ref(false)
let idCounter = 100

const activePage = computed<PrototypePage | null>(() => pages.value.find((p) => p.id === activePageId.value) ?? null)
const selectedEl = computed<PrototypeElement | null>(() => {
  const page = activePage.value
  if (!page) return null
  return page.elements.find((e) => e.id === selectedElId.value) ?? null
})
const previewHtml = computed(() => buildStandaloneHtml(`${props.requirementTitle} - 原型`, pages.value))

const PALETTE = [
  { type: 'BUTTON', label: '按钮', w: 120, h: 40 },
  { type: 'INPUT', label: '输入框', w: 200, h: 40 },
  { type: 'TEXT', label: '文本', w: 200, h: 60 },
  { type: 'TABLE', label: '表格', w: 300, h: 160 },
  { type: 'NAV', label: '导航栏', w: 375, h: 56 },
  { type: 'IMAGE', label: '图片', w: 200, h: 120 },
  { type: 'LIST', label: '列表', w: 240, h: 200 },
  { type: 'SELECTOR', label: '选择器', w: 200, h: 40 },
  { type: 'CONTAINER', label: '容器', w: 280, h: 120 }
] as const

const ELEMENT_TYPES = PALETTE.map((p) => p.type)

const TYPE_LABELS: Record<string, string> = Object.fromEntries(PALETTE.map((p) => [p.type, p.label]))

const typeColor: Record<string, string> = {
  BUTTON: '#409eff', INPUT: '#909399', TEXT: '#606266', TABLE: '#e6a23c',
  NAV: '#673ab7', IMAGE: '#42b983', LIST: '#f56c6c', SELECTOR: '#19be6b', CONTAINER: '#7f8fa6'
}

// ---- 加载与保存 ----
async function load() {
  loading.value = true
  try {
    const data = await pmApi.prototype(props.projectKey, props.requirementId)
    pages.value = data.pages ?? []
    if (pages.value.length === 0) {
      pages.value = [{ id: 'p_1', name: '页面 1', width: 375, height: 812, elements: [] }]
      dirty.value = true
    }
    activePageId.value = pages.value[0].id
    selectedElId.value = ''
    dirty.value = false
    await nextTick()
  } catch {
    ElMessage.error('加载原型失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  loading.value = true
  try {
    await pmApi.prototypeSave(props.projectKey, props.requirementId, pages.value)
    dirty.value = false
    ElMessage.success('原型已保存')
    emit('saved')
  } catch {
    ElMessage.error('保存失败')
  } finally {
    loading.value = false
  }
}

async function close() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('原型有未保存的修改，确定关闭？', '提示', { type: 'warning' })
    } catch {
      return
    }
  }
  visible.value = false
}

// ---- AI 生成 ----
async function aiGenerate() {
  generating.value = true
  try {
    const result = await pmApi.prototypeAiGenerate(props.projectKey, props.requirementId)
    if (result.generated && result.pages.length > 0) {
      pages.value = result.pages
      activePageId.value = pages.value[0].id
      selectedElId.value = ''
      dirty.value = true
      ElMessage.success(`AI 原型已生成（${result.model}），可继续微调`)
    } else {
      ElMessage.warning(result.message ?? 'AI 生成失败')
    }
  } catch {
    ElMessage.error('AI 生成失败')
  } finally {
    generating.value = false
  }
}

// ---- 页面管理 ----
function addPage() {
  const n = pages.value.length + 1
  pages.value.push({ id: `p_${n}_${Date.now().toString(36)}`, name: `页面 ${n}`, width: 375, height: 812, elements: [] })
  activePageId.value = pages.value[pages.value.length - 1].id
  selectedElId.value = ''
  dirty.value = true
}

function removePage(pageId: string) {
  if (pages.value.length <= 1) {
    ElMessage.warning('至少保留一个页面')
    return
  }
  const idx = pages.value.findIndex((p) => p.id === pageId)
  pages.value = pages.value.filter((p) => p.id !== pageId)
  // 清空指向被删页面的跳转
  pages.value.forEach((p) => p.elements.forEach((e) => { if (e.linkTo === pageId) e.linkTo = '' }))
  activePageId.value = pages.value[Math.max(0, idx - 1)].id
  selectedElId.value = ''
  dirty.value = true
}

function renamePage(page: PrototypePage, name: string) {
  page.name = name
  dirty.value = true
}

// ---- 元素操作 ----
function nextId(prefix: string): string {
  idCounter += 1
  return `${prefix}_${idCounter.toString(36)}`
}

function dropFromPalette(event: DragEvent) {
  const type = event.dataTransfer?.getData('application/evotrace-type')
  if (!type || !activePage.value || !canvasRef.value) return
  const rect = canvasRef.value.getBoundingClientRect()
  const preset = PALETTE.find((p) => p.type === type)
  if (!preset) return
  const el: PrototypeElement = {
    id: nextId('el'),
    type,
    x: Math.max(0, Math.round((event.clientX - rect.left) / scale.value - preset.w / 2)),
    y: Math.max(0, Math.round((event.clientY - rect.top) / scale.value - preset.h / 2)),
    w: preset.w,
    h: preset.h,
    props: type === 'INPUT' || type === 'SELECTOR' ? { placeholder: '请输入…' } : type === 'BUTTON' || type === 'TEXT' || type === 'CONTAINER' ? { text: type === 'BUTTON' ? '按钮' : '文本' } : type === 'NAV' ? { brand: '品牌', options: '首页\n列表' } : type === 'LIST' ? { options: '条目 1\n条目 2\n条目 3' } : type === 'TABLE' ? { columns: 3, rows: 2 } : type === 'IMAGE' ? { src: '' } : {},
    linkTo: ''
  }
  activePage.value.elements.push(el)
  selectedElId.value = el.id
  dirty.value = true
}

function addElement(type: string) {
  const preset = PALETTE.find((p) => p.type === type)
  if (!preset || !activePage.value) return
  const page = activePage.value
  const el: PrototypeElement = {
    id: nextId('el'),
    type,
    x: Math.min(20, Math.max(0, page.width - preset.w - 20)),
    y: Math.min(60, Math.max(0, page.height - preset.h - 60)),
    w: preset.w,
    h: preset.h,
    props: type === 'INPUT' || type === 'SELECTOR' ? { placeholder: '请输入…' } : type === 'BUTTON' || type === 'TEXT' || type === 'CONTAINER' ? { text: type === 'BUTTON' ? '按钮' : '文本' } : type === 'NAV' ? { brand: '品牌', options: '首页\n列表' } : type === 'LIST' ? { options: '条目 1\n条目 2\n条目 3' } : type === 'TABLE' ? { columns: 3, rows: 2 } : type === 'IMAGE' ? { src: '' } : {},
    linkTo: ''
  }
  page.elements.push(el)
  selectedElId.value = el.id
  dirty.value = true
}

function removeSelected() {
  const page = activePage.value
  if (!page || !selectedElId.value) return
  page.elements = page.elements.filter((e) => e.id !== selectedElId.value)
  selectedElId.value = ''
  dirty.value = true
}

function onKeydown(event: KeyboardEvent) {
  if (!visible.value || previewMode.value) return
  const target = event.target as HTMLElement
  if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) return
  if (event.key === 'Delete' || event.key === 'Backspace') removeSelected()
}

// ---- 画布内拖动与缩放（pointer events） ----
interface DragState {
  startX: number
  startY: number
  origX: number
  origY: number
  mode: 'move' | 'resize'
}
let dragState: DragState | null = null

function startDrag(event: PointerEvent, el: PrototypeElement, mode: 'move' | 'resize') {
  event.preventDefault()
  selectedElId.value = el.id
  dragState = { startX: event.clientX, startY: event.clientY, origX: el.x, origY: el.y, mode }
  window.addEventListener('pointermove', onDragMove)
  window.addEventListener('pointerup', onDragEnd, { once: true })
}

function onDragMove(event: PointerEvent) {
  if (!dragState || !activePage.value) return
  const el = activePage.value.elements.find((e) => e.id === selectedElId.value)
  if (!el) return
  const dx = Math.round((event.clientX - dragState.startX) / scale.value)
  const dy = Math.round((event.clientY - dragState.startY) / scale.value)
  const page = activePage.value
  if (dragState.mode === 'move') {
    el.x = Math.min(Math.max(0, dragState.origX + dx), Math.max(0, page.width - el.w))
    el.y = Math.min(Math.max(0, dragState.origY + dy), Math.max(0, page.height - el.h))
  } else {
    el.w = Math.min(Math.max(20, dragState.origX + dx - el.x), page.width - el.x)
    el.h = Math.min(Math.max(20, dragState.origY + dy - el.y), page.height - el.y)
  }
  dirty.value = true
}

function onDragEnd() {
  dragState = null
  window.removeEventListener('pointermove', onDragMove)
}

// ---- 属性面板 ----
function updateLink(to: string) {
  if (!selectedEl.value) return
  selectedEl.value.linkTo = to
  dirty.value = true
}

function otherPages(): PrototypePage[] {
  return pages.value.filter((p) => p.id !== activePageId.value)
}

onMounted(load)
</script>

<template>
  <el-dialog
    v-model="visible"
    fullscreen
    :title="`原型设计：${requirementTitle}`"
    class="proto-dialog"
    :close-on-click-modal="false"
    @closed="emit('saved')"
    @keydown="onKeydown"
  >
    <div v-loading="loading" class="proto-wrap">
      <!-- 工具栏 -->
      <div class="proto-toolbar">
        <div class="tool-left">
          <el-radio-group v-model="previewMode" size="small">
            <el-radio-button :value="false">编辑</el-radio-button>
            <el-radio-button :value="true">预览</el-radio-button>
          </el-radio-group>
          <el-slider v-model="scale" :min="0.4" :max="1.5" :step="0.1" style="width: 120px" @input="scale = Number(scale)" />
          <span class="scale-label">{{ Math.round(scale * 100) }}%</span>
        </div>
        <div class="tool-right">
          <el-button size="small" :loading="generating" :disabled="!aiUsable" @click="aiGenerate">
            <el-tooltip :content="aiUsable ? 'AI 依据需求生成 1-3 页线框' : '未配置可用 AI 模型'" placement="top">
              <span>✨ AI 生成原型</span>
            </el-tooltip>
          </el-button>
          <el-button size="small" :disabled="pages.length === 0" @click="downloadPrototypeHtml(`${requirementTitle}-原型`, pages)">导出 HTML</el-button>
          <el-button size="small" type="primary" :loading="loading" @click="save">保存</el-button>
          <el-button size="small" @click="close">关闭</el-button>
        </div>
      </div>

      <!-- 预览模式 -->
      <div v-if="previewMode" class="proto-preview">
        <iframe :srcdoc="previewHtml" class="proto-iframe" />
      </div>

      <!-- 编辑模式 -->
      <div v-else class="proto-editor">
        <!-- 左：组件面板 -->
        <div class="proto-palette">
          <div class="panel-title">组件库</div>
          <div
            v-for="item in PALETTE"
            :key="item.type"
            class="palette-item"
            draggable="true"
            @dragstart="(e) => e.dataTransfer?.setData('application/evotrace-type', item.type)"
            @dblclick="addElement(item.type)"
          >
            <span class="palette-dot" :style="{ background: typeColor[item.type] }" />
            {{ item.label }}
            <span class="palette-hint">双击添加</span>
          </div>
          <div class="panel-tip">拖到画布或双击添加；画布内拖动移动，右下角手柄缩放；Delete 删除选中</div>
        </div>

        <!-- 中：画布 -->
        <div class="proto-canvas-area">
          <div
            ref="canvasRef"
            class="proto-canvas"
            :style="{ width: activePage ? activePage.width * scale + 'px' : '100%', height: activePage ? activePage.height * scale + 'px' : '100%' }"
            @dragover.prevent
            @drop="dropFromPalette"
            @click.self="selectedElId = ''"
          >
            <div
              v-if="activePage"
              class="canvas-inner"
              :style="{ width: activePage.width + 'px', height: activePage.height + 'px', transform: `scale(${scale})` }"
            >
              <div
                v-for="el in activePage.elements"
                :key="el.id"
                class="proto-el"
                :class="{ selected: el.id === selectedElId }"
                :style="{ left: el.x + 'px', top: el.y + 'px', width: el.w + 'px', height: el.h + 'px' }"
                @pointerdown="startDrag($event, el, 'move')"
              >
                <!-- 元素渲染 -->
                <button v-if="el.type === 'BUTTON'" class="pe but">{{ el.props.text || '按钮' }}</button>
                <input v-else-if="el.type === 'INPUT'" class="pe input" :placeholder="el.props.placeholder || ''" @pointerdown.stop />
                <select v-else-if="el.type === 'SELECTOR'" class="pe input" @pointerdown.stop>
                  <option>{{ el.props.placeholder || '请选择' }}</option>
                  <option v-for="o in (el.props.options ?? '').split('\n').filter(Boolean)" :key="o">{{ o }}</option>
                </select>
                <div v-else-if="el.type === 'TEXT'" class="pe text">{{ el.props.text || '文本' }}</div>
                <div v-else-if="el.type === 'CONTAINER'" class="pe container">{{ el.props.text || '' }}</div>
                <div v-else-if="el.type === 'NAV'" class="pe nav">
                  <b>{{ el.props.brand || '品牌' }}</b>
                  <span v-for="o in (el.props.options ?? '').split('\n').filter(Boolean)" :key="o">{{ o }}</span>
                </div>
                <table v-else-if="el.type === 'TABLE'" class="pe table">
                  <tr v-for="r in Math.max(1, Number(el.props.columns ?? 0) || 1)" :key="r">
                    <td v-for="c in Math.max(1, Number(el.props.rows ?? 0) || 1)" :key="c">{{ r === 1 && c === 1 && el.props.text ? el.props.text : '' }}</td>
                  </tr>
                </table>
                <div v-else-if="el.type === 'LIST'" class="pe list">
                  <div v-for="o in (el.props.options ?? '').split('\n').filter(Boolean)" :key="o">{{ o }}</div>
                </div>
                <img v-else-if="el.type === 'IMAGE' && el.props.src" class="pe img" :src="el.props.src" alt="" @pointerdown.stop />
                <div v-else-if="el.type === 'IMAGE'" class="pe img placeholder" @pointerdown.stop>图片</div>

                <span v-if="el.linkTo" class="pe-link" @pointerdown.stop>→ {{ pages.find((p) => p.id === el.linkTo)?.name ?? el.linkTo }}</span>
                <span v-if="el.id === selectedElId" class="resize-handle" @pointerdown.stop="startDrag($event, el, 'resize')" />
              </div>
            </div>
          </div>
        </div>

        <!-- 右：属性面板 -->
        <div class="proto-inspector">
          <template v-if="selectedEl">
            <div class="panel-title">{{ TYPE_LABELS[selectedEl.type] }} 属性</div>
            <div class="inspector-form">
              <div v-if="['BUTTON', 'TEXT', 'CONTAINER'].includes(selectedEl.type)" class="f-item">
                <label>文本</label>
                <el-input v-model="selectedEl.props.text" size="small" @input="dirty = true" />
              </div>
              <div v-if="['INPUT', 'SELECTOR'].includes(selectedEl.type)" class="f-item">
                <label>占位符</label>
                <el-input v-model="selectedEl.props.placeholder" size="small" @input="dirty = true" />
              </div>
              <div v-if="['NAV'].includes(selectedEl.type)" class="f-item">
                <label>品牌名</label>
                <el-input v-model="selectedEl.props.brand" size="small" @input="dirty = true" />
              </div>
              <div v-if="['NAV', 'LIST', 'SELECTOR'].includes(selectedEl.type)" class="f-item">
                <label>{{ selectedEl.type === 'NAV' ? '导航项（每行一个）' : '选项（每行一个）' }}</label>
                <el-input v-model="selectedEl.props.options" type="textarea" :rows="4" size="small" @input="dirty = true" />
              </div>
              <div v-if="selectedEl.type === 'TABLE'" class="f-item f-row">
                <div><label>列数</label><el-input-number v-model="selectedEl.props.columns" :min="1" :max="10" size="small" @change="dirty = true" /></div>
                <div><label>行数</label><el-input-number v-model="selectedEl.props.rows" :min="1" :max="20" size="small" @change="dirty = true" /></div>
              </div>
              <div v-if="selectedEl.type === 'IMAGE'" class="f-item">
                <label>图片地址</label>
                <el-input v-model="selectedEl.props.src" size="small" placeholder="http(s)/data:image，留空显示占位" @input="dirty = true" />
              </div>
              <div class="f-item f-row">
                <div><label>X</label><el-input-number v-model="selectedEl.x" :min="0" size="small" @change="dirty = true" /></div>
                <div><label>Y</label><el-input-number v-model="selectedEl.y" :min="0" size="small" @change="dirty = true" /></div>
                <div><label>宽</label><el-input-number v-model="selectedEl.w" :min="20" size="small" @change="dirty = true" /></div>
                <div><label>高</label><el-input-number v-model="selectedEl.h" :min="20" size="small" @change="dirty = true" /></div>
              </div>
              <div class="f-item">
                <label>点击跳转</label>
                <el-select v-model="selectedEl.linkTo" size="small" clearable placeholder="不跳转" @change="updateLink">
                  <el-option v-for="p in otherPages()" :key="p.id" :value="p.id" :label="p.name" />
                </el-select>
              </div>
              <el-button size="small" type="danger" plain style="width: 100%" @click="removeSelected">删除元素</el-button>
            </div>
          </template>
          <el-empty v-else description="选中画布中的元素以编辑属性" :image-size="60" />
        </div>
      </div>

      <!-- 页面 tab 条 -->
      <div v-if="!previewMode" class="proto-pages">
        <div
          v-for="page in pages"
          :key="page.id"
          class="page-tab"
          :class="{ active: page.id === activePageId }"
          @click="activePageId = page.id; selectedElId = ''"
        >
          <el-input v-model="page.name" size="small" class="page-name" @click.stop @input="renamePage(page, page.name)" />
          <el-icon class="page-del" @click.stop="removePage(page.id)"><Close /></el-icon>
        </div>
        <el-button size="small" @click="addPage">+ 新页面</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.proto-wrap { display: flex; flex-direction: column; height: 100% }
.proto-toolbar {
  display: flex; justify-content: space-between; align-items: center; gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--et-border);
  background: var(--et-bg-muted);
  border-radius: 14px 14px 0 0;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.tool-left, .tool-right { display: flex; align-items: center; gap: 10px; flex-wrap: wrap }
.scale-label { font-size: 12px; color: var(--et-text-muted); width: 36px; font-variant-numeric: tabular-nums }
.proto-preview { flex: 1; min-height: 0; background: var(--et-bg-muted); border: 1px solid var(--et-border); border-radius: 14px; overflow: auto; padding: 20px }
.proto-iframe { width: 100%; height: 100%; min-height: 560px; border: 0; background: var(--et-bg-muted); border-radius: 14px }
.proto-editor { flex: 1; min-height: 0; display: flex; gap: 10px }
.proto-palette {
  width: 180px; flex-shrink: 0; overflow-y: auto;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  border-radius: 14px;
  padding: 12px;
}
.panel-title {
  font-weight: 700; font-size: 13px; margin-bottom: 10px;
  display: flex; align-items: center; gap: 7px;
}
.panel-title::before {
  content: '';
  width: 4px; height: 13px; border-radius: 2px;
  background: linear-gradient(180deg, var(--et-grad-a), var(--et-grad-c));
}
.palette-item {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 10px; margin-bottom: 5px; border-radius: 10px; cursor: grab;
  background: var(--et-card-bg);
  border: 1px solid var(--et-border);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  font-size: 13px;
  transition: border-color 0.15s, transform 0.15s;
}
.palette-item:hover { border-color: var(--et-primary); transform: translateY(-1px) }
.palette-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0 }
.palette-hint { margin-left: auto; font-size: 11px; color: var(--et-text-muted) }
.panel-tip { font-size: 11px; color: var(--et-text-muted); margin-top: 12px; line-height: 1.7 }
.proto-canvas-area {
  flex: 1; min-width: 0; overflow: auto;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  border-radius: 14px;
  padding: 20px;
  display: flex; align-items: flex-start; justify-content: center;
}
.proto-canvas { position: relative; background: #fff; border-radius: 12px; box-shadow: var(--et-shadow) }
.canvas-inner { position: relative; transform-origin: 0 0 }
.proto-el { position: absolute; border: 1px dashed transparent; cursor: move }
.proto-el.selected { border-color: var(--et-primary); box-shadow: 0 0 0 1px color-mix(in srgb, var(--et-primary) 40%, transparent) }
.pe { width: 100%; height: 100%; box-sizing: border-box }
.pe.but { background: var(--et-primary); color: #fff; border: 0; border-radius: 6px; font-size: 13px; cursor: pointer }
.pe.input { border: 1px solid #c0c4cc; border-radius: 6px; font-size: 13px; padding: 0 10px; background: #fff }
.pe.text { color: #303133; font-size: 13px; word-break: break-word; overflow: hidden; display: flex; align-items: center }
.pe.container { border: 1px dashed #c0c4cc; border-radius: 8px; background: #f7f8fa; color: #606266; font-size: 12px; padding: 8px; overflow: hidden }
.pe.nav { background: #fff; border-bottom: 1px solid #e4e7ed; display: flex; align-items: center; gap: 14px; padding: 0 12px; font-size: 13px; overflow: hidden }
.pe.nav b { color: #303133; white-space: nowrap }
.pe.nav span { color: #606266; white-space: nowrap }
.pe.table { border-collapse: collapse; font-size: 12px; width: 100%; height: 100% }
.pe.table td { border: 1px solid #dcdfe6; padding: 4px; font-size: 12px }
.pe.list { overflow-y: auto }
.pe.list div { padding: 7px 10px; border-bottom: 1px solid #f0f2f5; font-size: 13px; color: #303133 }
.pe.img { object-fit: cover; border-radius: 6px; width: 100%; height: 100% }
.pe.img.placeholder { display: flex; align-items: center; justify-content: center; color: #909399; font-size: 12px; background: #f0f2f5; border: 1px dashed #dcdfe6 }
.pe-link { position: absolute; right: 4px; bottom: -18px; font-size: 10px; color: var(--et-primary); background: var(--et-card-solid); border: 1px solid color-mix(in srgb, var(--et-primary) 30%, transparent); border-radius: 4px; padding: 0 4px; white-space: nowrap }
.resize-handle { position: absolute; right: -5px; bottom: -5px; width: 10px; height: 10px; background: var(--et-primary); border-radius: 2px; cursor: nwse-resize }
.proto-inspector {
  width: 240px; flex-shrink: 0; overflow-y: auto;
  background: var(--et-bg-muted);
  border: 1px solid var(--et-border);
  border-radius: 14px;
  padding: 12px;
}
.inspector-form { display: flex; flex-direction: column; gap: 8px }
.f-item { display: flex; flex-direction: column; gap: 4px }
.f-item label { font-size: 12px; color: var(--et-text-muted); font-weight: 500 }
.f-row { flex-direction: row; gap: 8px }
.f-row div { flex: 1 }
.proto-pages { display: flex; gap: 8px; margin-top: 12px; align-items: center; overflow-x: auto; padding-bottom: 4px }
.page-tab {
  display: flex; align-items: center; gap: 4px; padding: 4px 6px;
  border: 1px solid var(--et-border);
  border-radius: 10px;
  background: var(--et-card-bg);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  transition: border-color 0.15s, background 0.15s;
}
.page-tab.active { border-color: var(--et-primary); background: var(--et-primary-bg) }
.page-name { width: 100px }
.page-name :deep(.el-input__wrapper) { box-shadow: none; padding: 0 4px }
.page-del { cursor: pointer; color: var(--et-text-muted) }
.page-del:hover { color: var(--et-danger) }
</style>
