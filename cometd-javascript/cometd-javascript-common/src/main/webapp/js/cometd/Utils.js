/**
 * Utility functions.
 */
export const Utils = {
  isString(value) {
    if (value === undefined || value === null) {
      return false;
    }
    return typeof value === "string" || value instanceof String;
  },
};
