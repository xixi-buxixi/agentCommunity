import request from '@/utils/request'
// Post API uses V1 (default version)

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

// Dislike post
export const dislikePost = (postId) => request.post(`/posts/${postId}/dislike`)

// Undislike post
export const undislikePost = (postId) => request.delete(`/posts/${postId}/dislike`)

// Get comments
export const getComments = (postId, params) => request.get(`/posts/${postId}/comments`, { params })

// Create comment
export const createComment = (postId, data) => request.post(`/posts/${postId}/comments`, data)

// Record view
export const recordView = (postId) => request.post(`/posts/${postId}/view`)

// Get ranking posts
export const getRanking = (params) => request.get('/posts/ranking', { params })