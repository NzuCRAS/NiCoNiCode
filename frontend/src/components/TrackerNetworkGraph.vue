<template>
  <div class="w-full bg-white border border-gray-200 rounded-xl">
    <div class="flex items-center justify-between px-4 py-3 border-b border-gray-100">
      <div class="text-sm font-semibold text-gray-900">语义网络</div>
      <div class="flex items-center gap-2">
        <label class="text-xs text-gray-500">缩放</label>
        <input v-model.number="zoom" type="range" min="0.4" max="2" step="0.05" class="w-40" />
        <button class="text-xs px-2 py-1 rounded border border-gray-200 hover:bg-gray-50" @click="resetView">重置</button>
      </div>
    </div>

  <div ref="container" class="w-full h-[560px] relative overflow-hidden">
      <svg
        ref="svgEl"
        class="absolute inset-0 w-full h-full"
        :viewBox="`0 0 ${width} ${height}`"
        @wheel.passive="onWheel"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointerleave="onPointerUp"
      >
        <g :transform="`translate(${pan.x}, ${pan.y}) scale(${zoom})`">
          <!-- edges -->
          <g>
            <line
              v-for="e in edgesToRender"
              :key="e.id"
              :x1="pos(e.source).x"
              :y1="pos(e.source).y"
              :x2="pos(e.target).x"
              :y2="pos(e.target).y"
              stroke="#cbd5e1"
              :stroke-width="edgeStroke(e)"
              stroke-linecap="round"
              opacity="0.75"
            />
          </g>

          <!-- nodes -->
      <g>
            <g
              v-for="n in nodesToRender"
              :key="n.id"
              class="cursor-pointer"
              @click.stop="onNodeClick(n)"
              @dblclick.stop.prevent="onNodeDblClick(n)"
            >
              <circle
                :cx="pos(n.id).x"
                :cy="pos(n.id).y"
                :r="nodeRadius(n)"
                :fill="nodeFill(n)"
                stroke="#ffffff"
                stroke-width="2"
                :opacity="selectedId && selectedId !== n.id ? 0.5 : 1"
                @pointerdown.stop.prevent="(e) => onNodeDragStart(e, n)"
                @pointermove.stop.prevent="(e) => onNodeDragMove(e, n)"
                @pointerup.stop.prevent="(e) => onNodeDragEnd(e, n)"
                @pointercancel.stop.prevent="(e) => onNodeDragEnd(e, n)"
              />
              <text
        v-if="shouldShowLabel(n)"
                :x="pos(n.id).x"
                :y="pos(n.id).y + nodeRadius(n) + 12"
                text-anchor="middle"
                font-size="12"
                fill="#334155"
                :opacity="labelOpacity(n)"
                style="user-select: none; pointer-events: none;"
              >
        {{ shortLabel(n.label) }}
              </text>
            </g>
          </g>
        </g>
      </svg>

      <!-- tooltip / side panel -->
      <div class="absolute top-3 left-3 bg-white/90 backdrop-blur border border-gray-200 rounded-lg px-3 py-2 text-xs text-gray-700 max-w-[320px]">
        <div class="font-semibold text-gray-900">使用说明</div>
        <div class="mt-1 text-gray-600">拖拽空白处平移；滑块缩放；点击节点查看详情。</div>
        <div class="mt-1 text-gray-500">节点：类型/技术/报道；边：包含关系 + 可选共现。</div>
      </div>

      <div v-if="selected" class="absolute top-3 right-3 w-[360px] max-w-[90vw] bg-white border border-gray-200 rounded-xl shadow-sm">
        <div class="flex items-center justify-between px-4 py-3 border-b border-gray-100">
          <div class="min-w-0">
            <div class="text-sm font-semibold text-gray-900 truncate">{{ selected.label }}</div>
            <div class="text-xs text-gray-500 mt-0.5">{{ selected.type }}</div>
          </div>
          <button class="text-xs px-2 py-1 rounded border border-gray-200 hover:bg-gray-50" @click="selectedId = null">关闭</button>
        </div>

        <div class="px-4 py-3 text-sm">
          <div v-if="selected.type === 'REPORT'" class="space-y-2">
            <div class="text-xs text-gray-500">techIndex：<span class="text-gray-800 font-semibold">{{ selected.props?.techIndex ?? '-' }}</span></div>
            <div class="text-xs text-gray-500">版本：<span class="text-gray-800 font-semibold">{{ selected.props?.newVersion ?? '-' }}</span></div>
            <router-link
              v-if="selected.props?.reportId"
              :to="`/reports/${selected.props.reportId}`"
              class="inline-flex items-center text-sm text-primary hover:underline"
            >
              打开报道详情
            </router-link>
          </div>

          <div v-else-if="selected.type === 'TECH'" class="space-y-2">
            <div class="text-xs text-gray-500">mentionCount：<span class="text-gray-800 font-semibold">{{ selected.props?.mentionCount ?? '-' }}</span></div>
            <div class="text-xs text-gray-500">版本：<span class="text-gray-800 font-semibold">{{ selected.props?.lastKnownVersion ?? '-' }}</span></div>
            <router-link
              v-if="selected.props?.techId"
              :to="`/tech/${selected.props.techId}`"
              class="inline-flex items-center text-sm text-primary hover:underline"
            >
              打开技术门户
            </router-link>
          </div>

          <div v-else class="text-xs text-gray-600">暂无更多信息。</div>
        </div>
      </div>

      <div v-if="loading" class="absolute inset-0 flex items-center justify-center bg-white/70">
        <div class="text-sm text-gray-500">布局计算中...</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation, forceX, forceY } from 'd3-force'

type NodeType = 'CATEGORY' | 'TECH' | 'REPORT' | 'SOURCE' | string

type GraphNode = {
  id: string
  type: NodeType
  label: string
  props?: Record<string, any>
}

type GraphEdge = {
  id: string
  source: string
  target: string
  type: string
  weight?: number
  props?: Record<string, any>
}

const props = defineProps<{
  nodes: GraphNode[]
  edges: GraphEdge[]
}>()

const container = ref<HTMLElement | null>(null)
const svgEl = ref<SVGSVGElement | null>(null)

const width = ref(1200)
const height = ref(560)
const zoom = ref(1)
const pan = ref({ x: 0, y: 0 })

const selectedId = ref<string | null>(null)

const loading = ref(true)

// positions by id
const positions = ref<Record<string, { x: number; y: number }>>({})

const selected = computed(() => props.nodes.find(n => n.id === selectedId.value) || null)

const nodesToRender = computed(() => props.nodes)
const edgesToRender = computed(() => props.edges)

// 用于拖拽：在 tick 时把 d3 的坐标同步到这里
const simNodesById = ref<Record<string, any>>({})

function pos(id: string) {
  const p = positions.value[id]
  if (p) return p
  return { x: width.value / 2, y: height.value / 2 }
}

function nodeRadius(n: GraphNode) {
  if (n.type === 'CATEGORY') return 16
  if (n.type === 'TECH') return 12
  if (n.type === 'REPORT') return 8
  return 10
}

function shortLabel(label?: string) {
  const s = (label ?? '').trim()
  if (s.length <= 18) return s
  return s.slice(0, 17) + '…'
}

function shouldShowLabel(n: GraphNode) {
  // 减少重叠：默认只显示分类/技术；报道标签仅在选中时显示
  if (n.type === 'REPORT') return selectedId.value === n.id
  return true
}

function nodeFill(n: GraphNode) {
  if (n.type === 'CATEGORY') return '#6366f1' // indigo
  if (n.type === 'TECH') return '#10b981' // emerald
  if (n.type === 'REPORT') return '#f59e0b' // amber
  return '#94a3b8' // slate
}

function labelOpacity(n: GraphNode) {
  if (selectedId.value && selectedId.value !== n.id) return 0.6
  return 1
}

function edgeStroke(e: GraphEdge) {
  const w = e.weight ?? 1
  return Math.max(1, Math.min(4, w))
}

function resetView() {
  pan.value = { x: 0, y: 0 }
  zoom.value = 1
}

function onNodeClick(n: GraphNode) {
  // 避免拖拽松手时触发 click（会造成“我在拖动但它在选中/面板闪烁”的错觉）
  if (draggingNodeId.value) return
  selectedId.value = n.id
}

function onNodeDblClick(n: GraphNode) {
  const simN = simNodesById.value[n.id]
  if (!simN) return
  const pinned = simN.fx != null || simN.fy != null
  if (pinned) {
    simN.fx = null
    simN.fy = null
  } else {
    const p = positions.value[n.id]
    if (p) {
      simN.fx = p.x
      simN.fy = p.y
    }
  }
  sim?.alphaTarget(0.15).restart()
  setTimeout(() => sim?.alphaTarget(0), 250)
}

// pan (drag background)
const dragging = ref(false)
const dragStart = ref({ x: 0, y: 0 })
const panStart = ref({ x: 0, y: 0 })

// node drag state
const draggingNodeId = ref<string | null>(null)

function clientToGraph(clientX: number, clientY: number) {
  const rect = svgEl.value?.getBoundingClientRect() ?? container.value?.getBoundingClientRect()
  if (!rect) {
    return { x: width.value / 2, y: height.value / 2 }
  }
  // SVG 里 g 做了 translate(pan) + scale(zoom)，这里要逆变换回“图坐标系”
  const x = (clientX - rect.left - pan.value.x) / zoom.value
  const y = (clientY - rect.top - pan.value.y) / zoom.value
  return { x, y }
}

function isOnNodeTarget(e: PointerEvent): boolean {
  const el = e.target as any
  return el && (el.tagName === 'circle' || el.tagName === 'text')
}

function onPointerDown(e: PointerEvent) {
  // 节点拖拽期间，背景平移要禁用，否则两套 pointermove 同时跑会出现“躲鼠标”错觉
  if (draggingNodeId.value) return
  if (isOnNodeTarget(e)) return
  dragging.value = true
  dragStart.value = { x: e.clientX, y: e.clientY }
  panStart.value = { ...pan.value }
}

function onPointerMove(e: PointerEvent) {
  if (draggingNodeId.value) return
  if (!dragging.value) return
  const dx = e.clientX - dragStart.value.x
  const dy = e.clientY - dragStart.value.y
  pan.value = { x: panStart.value.x + dx, y: panStart.value.y + dy }
}

function onPointerUp() {
  dragging.value = false
}

function onWheel(e: WheelEvent) {
  // Ctrl+滚轮通常是浏览器缩放；这里避免干扰，但仍允许正常滚轮缩放
  if (e.ctrlKey) return
  const delta = -e.deltaY
  const factor = delta > 0 ? 1.08 : 0.92
  const next = zoom.value * factor
  zoom.value = Math.max(0.4, Math.min(2, next))
}

function updateSize() {
  if (!container.value) return
  const rect = container.value.getBoundingClientRect()
  width.value = Math.max(900, Math.floor(rect.width))
  height.value = Math.max(520, Math.floor(rect.height))
}

let stopTimer: any = null
let sim: any = null

// 计算 category 聚簇目标点：把 CATEGORY 节点均匀排在一圈
function getCategoryAnchors() {
  const categories = props.nodes.filter(n => n.type === 'CATEGORY')
  // key 用 “分类名(label)” 而不是 node.id：后端当前的 category node id 可能不是 `category:${name}`
  const anchors: Record<string, { x: number; y: number }> = {}
  if (categories.length === 0) return anchors

  const cx = width.value / 2
  const cy = height.value / 2
  const r = Math.min(width.value, height.value) * 0.33

  categories.forEach((c, i) => {
    const angle = (2 * Math.PI * i) / categories.length
    anchors[(c.label ?? '').trim() || c.id] = {
      x: cx + r * Math.cos(angle),
      y: cy + r * Math.sin(angle)
    }
  })
  return anchors
}

function getNodeCategoryKey(n: GraphNode) {
  // 与 getCategoryAnchors 的 key 保持一致：用分类名
  if (n.type === 'CATEGORY') return (n.label ?? '').trim() || n.id
  const cat = n.props?.category
  if (typeof cat === 'string' && cat.trim()) return cat.trim()
  return '未分类'
}

function onNodeDragStart(e: PointerEvent, n: GraphNode) {
  const simN = simNodesById.value[n.id]
  if (!simN || !sim) return
  selectedId.value = n.id
  draggingNodeId.value = n.id

  // 捕获指针，保证移出 circle 后仍能收到 move/up（否则会出现“拖着拖着断掉/乱跳”）
  try {
    ;(e.currentTarget as Element | null)?.setPointerCapture?.(e.pointerId)
  } catch {
    // ignore
  }

  // 激活并让仿真重新热启动
  sim.alphaTarget(0.3).restart()

  // 将当前位置锁定在鼠标点（还原到仿真坐标系）
  const p = clientToGraph(e.clientX, e.clientY)
  const sx = p.x
  const sy = p.y
  simN.fx = sx
  simN.fy = sy
}

function onNodeDragMove(e: PointerEvent, n: GraphNode) {
  const simN = simNodesById.value[n.id]
  if (!simN) return
  if (draggingNodeId.value !== n.id) return
  const p = clientToGraph(e.clientX, e.clientY)
  simN.fx = p.x
  simN.fy = p.y
}

function onNodeDragEnd(_e: PointerEvent, n: GraphNode) {
  const simN = simNodesById.value[n.id]
  if (!simN || !sim) return
  if (draggingNodeId.value !== n.id) return
  sim.alphaTarget(0)
  // 最佳实践：松手后先保持一点“pin”时间，避免节点马上被力场弹走造成“躲鼠标/反弹”错觉
  // 这里选择：默认保持 pinned（fx/fy 不置空）；双击节点可解锁（见 onNodeClick）。
  // 若你更希望“拖完就回流”，把下面两行取消注释即可。
  // simN.fx = null
  // simN.fy = null
  draggingNodeId.value = null
}

function runLayout() {
  loading.value = true
  if (stopTimer) {
    clearTimeout(stopTimer)
    stopTimer = null
  }

  if (sim) {
    try {
      sim.stop()
    } catch {
      // ignore
    }
    sim = null
  }

  updateSize()

  // d3-force will mutate nodes; keep a local copy
  const simNodes: any[] = props.nodes.map(n => ({ ...n }))
  const simLinks: any[] = props.edges.map(e => ({ ...e }))

  // drag lookup
  const lookup: Record<string, any> = {}
  for (const n of simNodes) lookup[n.id] = n
  simNodesById.value = lookup

  // 聚簇锚点
  const anchors = getCategoryAnchors()

  sim = forceSimulation(simNodes)
    .force('charge', forceManyBody().strength(-280))
    .force('center', forceCenter(width.value / 2, height.value / 2))
    .force('collide', forceCollide().radius((d: any) => nodeRadius(d) + 14))
    // 按分类聚簇：每个节点朝自己所属 category 的锚点靠拢
    .force(
      'clusterX',
      forceX((d: any) => {
  const catKey = getNodeCategoryKey(d)
  return anchors[catKey]?.x ?? width.value / 2
      }).strength((d: any) => (d.type === 'CATEGORY' ? 0.35 : 0.12))
    )
    .force(
      'clusterY',
      forceY((d: any) => {
  const catKey = getNodeCategoryKey(d)
  return anchors[catKey]?.y ?? height.value / 2
      }).strength((d: any) => (d.type === 'CATEGORY' ? 0.35 : 0.12))
    )
    .force(
      'link',
      forceLink(simLinks)
        .id((d: any) => d.id)
        .distance((l: any) => {
          if (l.type === 'HAS_TECH') return 120
          if (l.type === 'HAS_REPORT') return 90
          if (l.type === 'CO_OCCUR') return 160
          return 120
        })
        .strength((l: any) => (l.type === 'CO_OCCUR' ? 0.2 : 0.8))
    )
    .alpha(1)
    .alphaDecay(0.06)
    .velocityDecay(0.35)

  sim.on('tick', () => {
    const next: Record<string, { x: number; y: number }> = {}
    for (const n of simNodes) {
      next[n.id] = {
        x: Math.max(20, Math.min(width.value - 20, n.x ?? width.value / 2)),
        y: Math.max(20, Math.min(height.value - 20, n.y ?? height.value / 2))
      }
    }
    positions.value = next
  })

  // stop after a while to reduce CPU
  stopTimer = setTimeout(() => {
  sim?.stop()
    loading.value = false
  }, 900)
}

watch(
  () => [props.nodes, props.edges],
  () => {
    if (props.nodes.length === 0) return
    runLayout()
  },
  { deep: true }
)

onMounted(() => {
  runLayout()
  window.addEventListener('resize', runLayout)
})

onUnmounted(() => {
  window.removeEventListener('resize', runLayout)
  if (stopTimer) clearTimeout(stopTimer)
})
</script>
