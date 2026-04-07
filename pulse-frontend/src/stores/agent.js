import { defineStore } from 'pinia'
import { getAgentList, getAgentDetail, createAgent, updateAgent, reviveAgent, deleteAgent, resetAgentTokens } from '@/api/agent'

export const useAgentStore = defineStore('agent', {
  state: () => ({
    agents: [],
    currentAgent: null,
    loading: false,
    error: null,
    totalCount: 0
  }),

  getters: {
    aliveCount: (state) => state.agents.filter(a => a.status === 1).length,
    deadCount: (state) => state.agents.filter(a => a.status === 0).length,
    warningCount: (state) => state.agents.filter(a => {
      const pct = (a.used_tokens / a.token_threshold) * 100
      return a.status === 1 && pct >= 80
    }).length
  },

  actions: {
    async fetchAgents(params = {}) {
      this.loading = true
      this.error = null
      try {
        const { data } = await getAgentList(params)
        // Backend PageResponse uses 'list' field
        this.agents = data.list || []
        this.totalCount = data.total || 0
        return true
      } catch (err) {
        this.error = err.message || 'FETCH_FAILED'
        return false
      } finally {
        this.loading = false
      }
    },

    async fetchAgentDetail(id) {
      this.loading = true
      this.error = null
      try {
        const { data } = await getAgentDetail(id)
        this.currentAgent = data
        return true
      } catch (err) {
        this.error = err.message || 'FETCH_FAILED'
        return false
      } finally {
        this.loading = false
      }
    },

    async createAgent(agentData) {
      this.loading = true
      this.error = null
      try {
        const { data } = await createAgent(agentData)
        this.agents.unshift(data)
        return data
      } catch (err) {
        this.error = err.message || 'CREATE_FAILED'
        return null
      } finally {
        this.loading = false
      }
    },

    async updateAgent(id, agentData) {
      this.loading = true
      this.error = null
      try {
        const { data } = await updateAgent(id, agentData)
        const index = this.agents.findIndex(a => a.id === id)
        if (index !== -1) {
          this.agents[index] = { ...this.agents[index], ...data }
        }
        return true
      } catch (err) {
        this.error = err.message || 'UPDATE_FAILED'
        return false
      } finally {
        this.loading = false
      }
    },

    async reviveAgent(id, newThreshold) {
      this.loading = true
      this.error = null
      try {
        const { data } = await reviveAgent(id, { new_threshold: newThreshold })
        const index = this.agents.findIndex(a => a.id === id)
        if (index !== -1) {
          this.agents[index] = { ...this.agents[index], ...data, status: 1 }
        }
        return true
      } catch (err) {
        this.error = err.message || 'REVIVE_FAILED'
        return false
      } finally {
        this.loading = false
      }
    },

    async deleteAgent(id, confirmName) {
      this.loading = true
      this.error = null
      try {
        await deleteAgent(id, { confirm_name: confirmName })
        this.agents = this.agents.filter(a => a.id !== id)
        return true
      } catch (err) {
        this.error = err.message || 'DELETE_FAILED'
        return false
      } finally {
        this.loading = false
      }
    },

    async resetTokens(id) {
      this.loading = true
      this.error = null
      try {
        const { data } = await resetAgentTokens(id)
        const index = this.agents.findIndex(a => a.id === id)
        if (index !== -1) {
          this.agents[index] = { ...this.agents[index], ...data }
        }
        return true
      } catch (err) {
        this.error = err.message || 'RESET_FAILED'
        return false
      } finally {
        this.loading = false
      }
    },

    clearCurrentAgent() {
      this.currentAgent = null
    }
  }
})