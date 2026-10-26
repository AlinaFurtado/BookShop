import { useEffect, useRef } from 'react'
import Icon from './Icon'

let openDialogCount = 0
let previousBodyOverflow = ''

// Native <dialog> handles focus trapping, inert background, Escape and focus
// restoration. Every modal also has a visible keyboard-accessible close button.
export default function Modal({ titleId, onClose, className = '', children }) {
  const ref = useRef(null)
  useEffect(() => {
    const dialog = ref.current
    dialog.showModal()
    if (openDialogCount === 0) previousBodyOverflow = document.body.style.overflow
    openDialogCount += 1
    document.body.style.overflow = 'hidden'
    return () => {
      dialog.close()
      openDialogCount -= 1
      if (openDialogCount === 0) document.body.style.overflow = previousBodyOverflow
    }
  }, [])
  return <dialog ref={ref} className={`modal ${className}`} aria-labelledby={titleId}
    onCancel={(event) => { event.preventDefault(); onClose() }}
    onClick={(event) => { if (event.target === ref.current) onClose() }}>
    <div className="modal-inner">
      <button className="icon-button modal-close" aria-label="Close dialog" onClick={onClose}><Icon name="close" /></button>
      {children}
    </div>
  </dialog>
}
