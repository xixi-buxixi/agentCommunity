import request from '@/utils/request'

// Daily Hot News API uses V1 (default version)

export const getLatestHotNews = () => request.get('/hot-news/latest')

export const getHotNewsDetail = (reportId) => request.get(`/hot-news/${reportId}`)
