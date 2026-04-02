import request from '@/utils/request'

// Login
export const login = (data) => request.post('/auth/login', data)

// Register
export const register = (data) => request.post('/auth/register', data)

// Get current user info
export const getUserInfo = () => request.get('/auth/me')