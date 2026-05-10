/**
 * Form Validation Utilities
 * Provides validation rules and helpers for form inputs
 */

// Email validation regex
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

// Username validation: alphanumeric + underscore, 3-20 chars
const USERNAME_REGEX = /^[a-zA-Z0-9_]{3,20}$/

/**
 * Validation rule types
 */
export const ValidationRules = {
  required: (value, fieldName = 'Field') => {
    if (value === null || value === undefined || value === '') {
      return `${fieldName} is required`
    }
    if (typeof value === 'string' && value.trim() === '') {
      return `${fieldName} is required`
    }
    return null
  },

  email: (value, fieldName = 'Email') => {
    if (!value) return null // Let required handle empty
    if (!EMAIL_REGEX.test(value)) {
      return `${fieldName} format is invalid`
    }
    return null
  },

  username: (value, fieldName = 'Username') => {
    if (!value) return null
    if (!USERNAME_REGEX.test(value)) {
      return `${fieldName} must be 3-20 characters, alphanumeric and underscore only`
    }
    return null
  },

  minLength: (value, min, fieldName = 'Field') => {
    if (typeof value === 'number' && (min === undefined || typeof min === 'string')) {
      return (actualValue) => ValidationRules.minLength(actualValue, value, min || fieldName)
    }
    if (!value) return null
    if (typeof value === 'string' && value.length < min) {
      return `${fieldName} must be at least ${min} characters`
    }
    return null
  },

  maxLength: (value, max, fieldName = 'Field') => {
    if (typeof value === 'number' && (max === undefined || typeof max === 'string')) {
      return (actualValue) => ValidationRules.maxLength(actualValue, value, max || fieldName)
    }
    if (!value) return null
    if (typeof value === 'string' && value.length > max) {
      return `${fieldName} must be at most ${max} characters`
    }
    return null
  },

  password: (value, fieldName = 'Password') => {
    if (!value) return null
    if (value.length < 6) {
      return `${fieldName} must be at least 6 characters`
    }
    if (value.length > 100) {
      return `${fieldName} must be at most 100 characters`
    }
    // Check for at least one letter and one number
    if (!/[a-zA-Z]/.test(value) || !/[0-9]/.test(value)) {
      return `${fieldName} must contain at least one letter and one number`
    }
    return null
  },

  numberRange: (value, min, max, fieldName = 'Field') => {
    if (typeof value === 'number' && typeof min === 'number' && (max === undefined || typeof max === 'string')) {
      return (actualValue) => ValidationRules.numberRange(actualValue, value, min, max || fieldName)
    }
    if (value === null || value === undefined || value === '') return null
    const num = Number(value)
    if (isNaN(num)) {
      return `${fieldName} must be a valid number`
    }
    if (num < min) {
      return `${fieldName} must be at least ${min}`
    }
    if (num > max) {
      return `${fieldName} must be at most ${max}`
    }
    return null
  },

  integer: (value, fieldName = 'Field') => {
    if (value === null || value === undefined || value === '') return null
    const num = Number(value)
    if (isNaN(num) || !Number.isInteger(num)) {
      return `${fieldName} must be an integer`
    }
    return null
  },

  positiveNumber: (value, fieldName = 'Field') => {
    if (value === null || value === undefined || value === '') return null
    const num = Number(value)
    if (isNaN(num) || num <= 0) {
      return `${fieldName} must be a positive number`
    }
    return null
  }
}

/**
 * Validate multiple rules against a value
 * @param {*} value - The value to validate
 * @param {Array<Function>} rules - Array of validation rule functions
 * @returns {string|null} First error message or null if valid
 */
export const validate = (value, rules) => {
  for (const rule of rules) {
    if (typeof rule !== 'function') {
      continue
    }
    const error = rule(value)
    if (error) return error
  }
  return null
}

/**
 * Validate an object with multiple fields
 * @param {Object} data - Object with field values
 * @param {Object} schema - Object with field names mapping to rule arrays
 * @returns {Object} Object with field names mapping to error messages (null if valid)
 */
export const validateObject = (data, schema) => {
  const errors = {}
  for (const [field, rules] of Object.entries(schema)) {
    errors[field] = validate(data[field], rules)
  }
  return errors
}

/**
 * Check if validation result has any errors
 * @param {Object} errors - Validation errors object
 * @returns {boolean} True if any errors exist
 */
export const hasErrors = (errors) => {
  return Object.values(errors).some(error => error !== null)
}

/**
 * Get all error messages as array
 * @param {Object} errors - Validation errors object
 * @returns {Array<string>} Array of error messages
 */
export const getErrorMessages = (errors) => {
  return Object.entries(errors)
    .filter(([_, error]) => error !== null)
    .map(([field, error]) => error)
}

/**
 * Create a reactive validation state helper for Vue
 * @param {Object} formData - Reactive form data object
 * @param {Object} schema - Validation schema
 * @returns {Object} { errors, validateField, validateAll, isValid }
 */
export const createValidationState = (formData, schema) => {
  const errors = {}

  const validateField = (field) => {
    errors[field] = validate(formData[field], schema[field] || [])
    return errors[field]
  }

  const validateAll = () => {
    for (const field of Object.keys(schema)) {
      validateField(field)
    }
    return !hasErrors(errors)
  }

  const isValid = () => !hasErrors(errors)

  return { errors, validateField, validateAll, isValid }
}
