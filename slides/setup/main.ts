import { defineAppSetup } from '@slidev/types'
import CodeWithScript from '../components/CodeWithScript.vue'
import JsonOverlay from '../components/JsonOverlay.vue'
import CtxWindow from '../components/CtxWindow.vue'

export default defineAppSetup(({ app, router }) => {
  app.component('CodeWithScript', CodeWithScript)
  app.component('JsonOverlay', JsonOverlay)
  app.component('CtxWindow', CtxWindow)

  router.afterEach(() => {
    document.documentElement.style.setProperty('--code-font-size-px', '14px')
  })
})
