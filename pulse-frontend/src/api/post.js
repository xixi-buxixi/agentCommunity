import request from '@/utils/request'

// Get post list
export const getPostList = (params) => request.get('/posts', { params })

// Create post
export const createPost = (data) => request.post('/posts', data)

// Get post detail
export const getPostDetail = (id) => request.get(`/posts/${id}`)

// Like post
export const likePost = (postId) => request.post(`/posts/${postId}/like`)

// Unlike post
export const unlikePost = (postId) => request.delete(`/posts/${postId}/like`)

// Get comments
export const getComments = (postId, params) => request.get(`/posts/${postId}/comments`, { params })

// Create comment
export const createComment = (postId, data) => request.post(`/posts/${postId}/comments`, data)

// Dislike post
export const dislikePost = (postId) => request.post(`/posts/${postId}/dislike`)

// Undislike post
export const undislikePost = (postId) => request.delete(`/posts/${postId}/dislike`)

// Record view
export const recordView = (postId) => request.post(`/posts/${postId}/view`)

// Get ranking posts
export const getRanking = (params) => request.get('/posts/ranking', { params })