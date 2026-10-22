<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'

/**
 * 3D 肌肉图。
 *
 * ## 数据从哪来
 *
 * `src/assets/anatomy/muscles.glb` 里的 17 个对象，对象名就是后端肌群表里的
 * `muscleGroupId`（chest / lats / ...）。导出管线在 `scripts/3d/`，
 * 模型来源与许可见 `docs/2026-10-21-3D模型来源与许可.md`。
 *
 * ## 为什么这个组件自己管渲染循环
 *
 * 只有一个 3D 视图，没有复用需求；抽成一个通用的「场景容器」再加一层 props
 * 只会让「哪个肌群该亮」这条逻辑散在两处。所以这里一次性把「加载 → 上色 →
 * 点选 → 清理」做完，对外只暴露 `volumeByMuscle` 进去、`muscle-click` 出来，
 * 与 2D 的 `<BodyMap />` 契约一致。
 *
 * ## 为什么颜色算在 CPU 上
 *
 * 17 个对象各自一个材质实例。用顶点着色器按属性上色也行，但那要求改几何体
 * 属性，而这里的颜色只随「本周组数」变一次——每帧重算没有意义。
 */
const props = defineProps<{
  /** 肌群 id -> 本周折算组数。缺项按 0 处理。 */
  volumeByMuscle: Record<string, number>
}>()
const emit = defineEmits<{ (e: 'muscle-click', id: string): void }>()

const host = ref<HTMLElement | null>(null)
const status = ref<'loading' | 'ready' | 'failed'>('loading')
const message = ref('')

// three 的对象不放进 reactive：代理一层会拖慢每帧的矩阵更新
const scene = shallowRef<unknown>(null)
let dispose: (() => void) | null = null

/** 与 2D 的那套色阶保持一致：未练是暖灰，练得越多越接近铜色。 */
const UNTRAINED = 0x3a342c
const SELECTED_EMISSIVE = 0x5a3d12

function toneFor(sets: number): number {
  if (sets <= 0) return UNTRAINED
  const t = Math.min(1, sets / 10)
  const lerp = (a: number, b: number) => Math.round(a + (b - a) * t)
  return (lerp(0x3a, 0xd9) << 16) | (lerp(0x34, 0xa4) << 8) | lerp(0x2c, 0x41)
}

let selected: string | null = null

async function build() {
  if (host.value === null) return
  try {
    // 动态 import：three 有 ~600 KB，而 2D 模式完全用不到它。
    // 静态 import 会让每个页面首屏都背上这个包。
    const THREE = await import('three')
    const { GLTFLoader } = await import('three/examples/jsm/loaders/GLTFLoader.js')

    const el = host.value
    const width = el.clientWidth || 640
    const height = el.clientHeight || 560

    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    renderer.setSize(width, height)
    el.appendChild(renderer.domElement)

    const sc = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(32, width / height, 0.1, 200)

    sc.add(new THREE.AmbientLight(0xffffff, 1.4))
    const key = new THREE.DirectionalLight(0xffffff, 2.2)
    key.position.set(2, 3, 4)
    sc.add(key)
    const fill = new THREE.DirectionalLight(0xffe6c0, 0.9)
    fill.position.set(-3, 1, -2)
    sc.add(fill)

    const loader = new GLTFLoader()
    const gltf = await loader.loadAsync(new URL('../assets/anatomy/muscles.glb', import.meta.url).href)
    const root = gltf.scene
    sc.add(root)

    // 相机按包围盒自动取景：模型的原点与比例不该由前端猜
    const box = new THREE.Box3().setFromObject(root)
    const size = box.getSize(new THREE.Vector3())
    const center = box.getCenter(new THREE.Vector3())
    root.position.sub(center)
    const dist = Math.max(size.x, size.y, size.z) * 2.2
    camera.position.set(0, 0, dist)
    camera.lookAt(0, 0, 0)

    const materials = new Map<string, InstanceType<typeof THREE.MeshStandardMaterial>>()
    root.traverse((o: { isMesh?: boolean; name?: string; material?: unknown }) => {
      if (o.isMesh !== true) return
      const name = o.name ?? ''
      const mat = new THREE.MeshStandardMaterial({
        color: toneFor(props.volumeByMuscle[name] ?? 0),
        roughness: 0.72,
        metalness: 0.08,
      })
      // 双面：导出的网格里有些是单面壳，背面剔除会露出空洞
      mat.side = THREE.DoubleSide
      ;(o as { material: unknown }).material = mat
      materials.set(name, mat)
    })

    const raycaster = new THREE.Raycaster()
    const pointer = new THREE.Vector2()

    function onPointerDown(ev: PointerEvent) {
      const rect = renderer.domElement.getBoundingClientRect()
      pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1
      pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1
      raycaster.setFromCamera(pointer, camera)
      const hits = raycaster.intersectObjects(root.children, true)
      if (hits.length === 0) return
      const name = hits[0].object.name
      if (!materials.has(name)) return
      emit('muscle-click', name)
    }
    renderer.domElement.addEventListener('pointerdown', onPointerDown)

    function onResize() {
      if (host.value === null) return
      const w = host.value.clientWidth || width
      const h = host.value.clientHeight || height
      renderer.setSize(w, h)
      camera.aspect = w / h
      camera.updateProjectionMatrix()
    }
    window.addEventListener('resize', onResize)

    // 缓慢自转：静止的 3D 很难看出它是 3D
    let raf = 0
    const loop = () => {
      root.rotation.y += 0.0022
      renderer.render(sc, camera)
      raf = requestAnimationFrame(loop)
    }
    loop()

    scene.value = sc
    status.value = 'ready'

    dispose = () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', onResize)
      renderer.domElement.removeEventListener('pointerdown', onPointerDown)
      renderer.dispose()
      if (renderer.domElement.parentNode === el) el.removeChild(renderer.domElement)
    }
  } catch (e) {
    // WebGL 不可用（老显卡 / 远程桌面 / 测试环境）时不能整页白掉：
    // 上层据此退回 2D。
    status.value = 'failed'
    message.value = e instanceof Error ? e.message : String(e)
  }
}

/** 组数变了就重上色。选中态单独走一层，两者互不覆盖。 */
function repaint() {
  const sc = scene.value
  if (sc === null) return
  ;(sc as { traverse: (cb: (o: unknown) => void) => void }).traverse((o) => {
    const mesh = o as { isMesh?: boolean; name?: string; material?: { color?: { setHex: (n: number) => void }; emissive?: { setHex: (n: number) => void } } }
    if (mesh.isMesh !== true || mesh.material === undefined) return
    const name = mesh.name ?? ''
    mesh.material.color?.setHex(toneFor(props.volumeByMuscle[name] ?? 0))
    mesh.material.emissive?.setHex(name === selected ? SELECTED_EMISSIVE : 0x000000)
  })
}

watch(() => props.volumeByMuscle, repaint, { deep: true })

onMounted(() => {
  void build()
})

onBeforeUnmount(() => {
  dispose?.()
  dispose = null
})

defineExpose({ status, message })
</script>

<template>
  <div class="wrap">
    <div ref="host" class="canvas" />
    <p v-if="status === 'loading'" class="hint">正在加载 3D 模型…</p>
    <p v-if="status === 'failed'" class="err">
      3D 渲染不可用（{{ message }}）。切回 2D 看本周容量。
    </p>
  </div>
</template>

<style scoped>
.wrap { position: relative; width: 100%; min-height: 520px; }
.canvas { width: 100%; height: 520px; }
.hint { position: absolute; inset: 0 0 auto; text-align: center; color: var(--text-dim); font-size: 12px; }
.err { position: absolute; left: 0; right: 0; bottom: 8px; text-align: center; color: var(--danger); font-size: 12px; }
</style>
