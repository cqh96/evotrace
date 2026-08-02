import { defineStore } from 'pinia'

/**
 * 当前工作项目（全局共享）。
 * 侧边栏选择器与各业务页面统一从这里读取，替代各处硬编码的 'mall'。
 * 选择持久化到 localStorage，刷新后保留。
 */
export const useProjectStore = defineStore('project', {
  state: () => ({
    current: localStorage.getItem('evotrace_project') ?? 'mall'
  }),
  actions: {
    setCurrent(key: string) {
      this.current = key
      localStorage.setItem('evotrace_project', key)
    }
  }
})
