<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'

/**
 * 3D 肌肉图。
 *
 * ## 数据从哪来
 *
 * `src/assets/anatomy/muscles.glb` 里的 17 个对象，对象名就是后端肌群表的
 * `nameEn`（chest / lats / …）。导出管线在 `scripts/3d/`，
 * 模型来源与许可见 `docs/2026-10-21-3D模型来源与许可.md`。
 *
 * ## 为什么自己管渲染循环
 *
 * 只有一个 3D 视图，没有复用需求；抽成通用「场景容器」只会让「哪个肌群该亮」
 * 散在两处。这里一次性把「加载 → 上色 → 轨道控制 → 点选 → 清理」做完，
 * 对外只暴露 `volumeByMuscle` 进去、`muscle-click` 出来，与 2D 的契约一致。
 *
 * ## 为什么点选用 pointerup + 位移阈值
 *
 * 第一版把点击挂在 `pointerdown` 上，而当时**没有轨道控制器**，于是用户
 * 既转不动、也点不中。加了 OrbitControls 之后，按下-拖动是旋转、
 * 按下-抬起不动才是点击，两者必须分开：否则每次拖完都会顺带选中一块肌肉。
 */
const props = defineProps<{
  /** 肌群 nameEn -> 本周折算组数。缺项按 0 处理。 */
  volumeByMuscle: Record<string, number>
}>()
const emit = defineEmits<{
  (e: 'muscle-click', id: string): void
  /** 点中了，但这个名字在肌群表里找不到。上层据此提示，而不是静默无事发生。 */
  (e: 'unknown-hit', name: string): void
}>()

const host = ref<HTMLElement | null>(null)
const status = ref<'loading' | 'ready' | 'failed'>('loading')
const message = ref('')

const scene = shallowRef<unknown>(null)
let dispose: (() => void) | null = null

/** 皮肤对象名。见 build() 里的说明。 */
const SKIN = 'skin'

/** 与 2D 的色阶同源：未练是暗暖灰，练得越多越接近铜色。 */
const UNTRAINED = 0x3a342c

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
    // 动态 import：three 有 ~600 KB，2D 模式完全用不到。
    // 静态 import 会让每个页面首屏都背上它。
    const THREE = await import('three')
    const { GLTFLoader } = await import('three/examples/jsm/loaders/GLTFLoader.js')
    const { OrbitControls } = await import('three/examples/jsm/controls/OrbitControls.js')

    const el = host.value
    const width = el.clientWidth || 640
    const height = el.clientHeight || 620

    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    renderer.setSize(width, height)
    el.appendChild(renderer.domElement)

    const sc = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(30, width / height, 0.1, 500)

    sc.add(new THREE.AmbientLight(0xffffff, 1.5))
    const key = new THREE.DirectionalLight(0xffffff, 2.4)
    key.position.set(2, 3, 4)
    sc.add(key)
    const fill = new THREE.DirectionalLight(0xffe6c0, 1.0)
    fill.position.set(-3, 1, -2)
    sc.add(fill)
    // 背光：只靠正面光时轮廓会糊在背景里
    const rim = new THREE.DirectionalLight(0xffd9a0, 0.7)
    rim.position.set(0, 2, -5)
    sc.add(rim)

    const loader = new GLTFLoader()
    const gltf = await loader.loadAsync(new URL('../assets/anatomy/muscles.glb', import.meta.url).href)
    const root = gltf.scene
    sc.add(root)

    // 相机按包围盒自动取景。1.45 是「人体占满容器高度、四周留一点气」的系数；
    // 第一版用 2.2，结果人只占中间窄窄一条。
    const box = new THREE.Box3().setFromObject(root)
    const size = box.getSize(new THREE.Vector3())
    const center = box.getCenter(new THREE.Vector3())
    root.position.sub(center)
    const span = Math.max(size.x, size.y, size.z)
    const dist = span * 1.45
    camera.position.set(0, 0, dist)
    camera.lookAt(0, 0, 0)

    // 轨道控制：拖动旋转、滚轮缩放。**关掉 autoRotate** —— 用户要求自己控制。
    const controls = new OrbitControls(camera, renderer.domElement)
    controls.enableDamping = true
    controls.dampingFactor = 0.08
    controls.enablePan = false
    controls.minDistance = span * 0.7
    controls.maxDistance = span * 3
    controls.target.set(0, 0, 0)

    // 皮肤层：`skin` 是唯一一个不参与「本周组数」着色的对象。
    // 它来自 BodyParts3D（CC BY 4.0），Z-Anatomy 自己没有皮肤网格。
    //
    // 三个参数是刻意的：
    //   - opacity 0.22：再高就把肌肉糊住，再低就看不出是个人
    //   - depthWrite: false：皮肤不写深度，肌肉才能透过来；否则按渲染顺序
    //     后画的皮肤会把肌肉挡掉
    //   - side: DoubleSide：只有一层壳，背面剔掉会从内部看到空洞
    const skinMat = new THREE.MeshPhysicalMaterial({
      color: 0xe6b49a,
      transparent: true,
      opacity: 0.22,
      roughness: 0.5,
      metalness: 0,
      side: THREE.DoubleSide,
      depthWrite: false,
    })

    const materials = new Map<string, InstanceType<typeof THREE.MeshStandardMaterial>>()
    root.traverse((o: { isMesh?: boolean; name?: string }) => {
      if (o.isMesh !== true) return
      const name = o.name ?? ''
      if (name === SKIN) {
        ;(o as { material: unknown }).material = skinMat
        return
      }
      const mat = new THREE.MeshStandardMaterial({
        color: toneFor(props.volumeByMuscle[name] ?? 0),
        roughness: 0.68,
        metalness: 0.1,
        // 双面：导出的网格里有些是单面壳，背面剔除会露出空洞
        side: THREE.DoubleSide,
      })
      ;(o as { material: unknown }).material = mat
      materials.set(name, mat)
    })

    const raycaster = new THREE.Raycaster()
    const pointer = new THREE.Vector2()
    let downX = 0
    let downY = 0

    function onDown(ev: PointerEvent) {
      downX = ev.clientX
      downY = ev.clientY
    }

    function onUp(ev: PointerEvent) {
      // 拖动超过 6px 就是「转视角」，不是「点选」。
      // 没有这个判断，每次拖完都会顺带选中一块肌肉。
      if (Math.hypot(ev.clientX - downX, ev.clientY - downY) > 6) return

      const rect = renderer.domElement.getBoundingClientRect()
      pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1
      pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1
      raycaster.setFromCamera(pointer, camera)
      const hits = raycaster.intersectObjects(root.children, true)
      // **必须跳过皮肤**：它是半透明的外壳，射线第一个打到的总是它。
      // 不跳过的话每次点击都会命中 `skin`，然后走「不在肌群表里」那条分支。
      const muscleHit = hits.find((h) => h.object.name !== SKIN)
      if (muscleHit === undefined) return

      const name = muscleHit.object.name
      if (!materials.has(name)) {
        // 点到了但名字不在肌群表里：告诉上层，别静默忽略
        emit('unknown-hit', name)
        return
      }
      emit('muscle-click', name)
    }

    renderer.domElement.addEventListener('pointerdown', onDown)
    renderer.domElement.addEventListener('pointerup', onUp)

    function onResize() {
      if (host.value === null) return
      const w = host.value.clientWidth || width
      const h = host.value.clientHeight || height
      renderer.setSize(w, h)
      camera.aspect = w / h
      camera.updateProjectionMatrix()
    }
    window.addEventListener('resize', onResize)

    let raf = 0
    const loop = () => {
      controls.update()   // 阻尼需要每帧喂
      renderer.render(sc, camera)
      raf = requestAnimationFrame(loop)
    }
    loop()

    scene.value = sc
    status.value = 'ready'

    dispose = () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', onResize)
      renderer.domElement.removeEventListener('pointerdown', onDown)
      renderer.domElement.removeEventListener('pointerup', onUp)
      controls.dispose()
      renderer.dispose()
      if (renderer.domElement.parentNode === el) el.removeChild(renderer.domElement)
    }
  } catch (e) {
    // WebGL 不可用（老显卡 / 远程桌面 / 测试环境）时不能整页白掉
    status.value = 'failed'
    message.value = e instanceof Error ? e.message : String(e)
  }
}

/** 组数变了就重上色。选中态单独一层，两者互不覆盖。 */
function repaint() {
  const sc = scene.value
  if (sc === null) return
  ;(sc as { traverse: (cb: (o: unknown) => void) => void }).traverse((o) => {
    const mesh = o as {
      isMesh?: boolean
      name?: string
      material?: { color?: { setHex: (n: number) => void }; emissive?: { setHex: (n: number) => void } }
    }
    if (mesh.isMesh !== true || mesh.material === undefined) return
    const name = mesh.name ?? ''
    if (name === SKIN) return   // 皮肤不随组数变色
    mesh.material.color?.setHex(toneFor(props.volumeByMuscle[name] ?? 0))
    mesh.material.emissive?.setHex(name === selected ? 0x5a3d12 : 0x000000)
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
    <p v-if="status === 'ready'" class="tip">拖动旋转 · 滚轮缩放 · 点肌肉看动作</p>
    <p v-if="status === 'failed'" class="err">
      3D 渲染不可用（{{ message }}）。切回 2D 看本周容量。
    </p>
  </div>
</template>

<style scoped>
.wrap { position: relative; width: 100%; }
.canvas { width: 100%; height: 620px; }
.hint {
  position: absolute; inset: 0 0 auto; text-align: center;
  color: var(--text-dim); font-size: 12px; padding-top: 40px;
}
.tip {
  position: absolute; left: 0; right: 0; bottom: 4px; text-align: center;
  color: var(--eyebrow); font-size: 10px; margin: 0; pointer-events: none;
}
.err {
  position: absolute; left: 0; right: 0; bottom: 8px; text-align: center;
  color: var(--danger); font-size: 12px;
}
</style>
