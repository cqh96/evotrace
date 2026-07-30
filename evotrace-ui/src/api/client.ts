import axios from 'axios'
import { ElMessage } from 'element-plus'

const client = axios.create({ baseURL: '/api/v1', timeout: 30000 })

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('evotrace_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

client.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'success' in body) {
      if (body.success) return body.data
      ElMessage.error(body.message ?? '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return body
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('evotrace_token')
      window.location.href = '/login'
    }
    ElMessage.error(error.response?.data?.message ?? error.message ?? '请求失败')
    return Promise.reject(error)
  }
)

export default client
