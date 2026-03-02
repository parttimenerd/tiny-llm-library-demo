import { defineAppSetup } from '@slidev/cli'
import CodeWithScript from '../components/CodeWithScript.vue'

export default defineAppSetup(({ app }) => {
  app.component('CodeWithScript', CodeWithScript)
})
