import { ref } from 'vue'

const theme = ref(localStorage.getItem('theme') || 'light')

if (theme.value === 'dark') document.body.classList.add('dark')

export function useTheme() {
  function toggleTheme() {
    if (theme.value === 'light') {
      theme.value = 'dark'
      document.body.classList.add('dark')
    } else {
      theme.value = 'light'
      document.body.classList.remove('dark')
    }
    localStorage.setItem('theme', theme.value)
  }
  return { theme, toggleTheme }
}
