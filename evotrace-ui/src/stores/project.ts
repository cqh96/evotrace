import { defineStore } from 'pinia'

/**
 * 当前工作项目（全局共享）。
 * 侧边栏选择器与各业务页面统一从这里读取，不再硬编码默认项目。
 * 选择持久化到 localStorage，刷新后保留；无有效选择时由 MainLayout 自动选中第一个在线项目。
 */
export const useProjectStore = defineStore('project', {
  state: () => ({
    current: localStorage.getItem('evotrace_project') ?? ''
  }),
  actions: {
    setCurrent(key: string) {
      this.current = key
      localStorage.setItem('evotrace_project', key)
    }
  }
})
