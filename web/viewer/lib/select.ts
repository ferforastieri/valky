// Progressive enhancement: the native select remains the form value and
// dispatches change, while the visible control follows the project's surfaces.
let selectSequence = 0;

export function styleSelects(root: HTMLElement): () => void {
  const cleanups: (() => void)[] = [];
  root.querySelectorAll<HTMLSelectElement>('select:not([data-styled])').forEach(select => {
    select.dataset.styled = 'true';
    const wrapper = document.createElement('div'); wrapper.className = 'project-select';
    const button = document.createElement('button'); button.type = 'button'; button.className = 'select-trigger';
    button.setAttribute('aria-haspopup', 'listbox'); button.setAttribute('aria-expanded', 'false');
    const list = document.createElement('div'); list.className = 'select-options'; list.role = 'listbox'; list.hidden = true;
    list.id = `${select.id || 'select'}-${++selectSequence}`; button.setAttribute('aria-controls', list.id);
    select.before(wrapper); wrapper.append(select, button, list); select.hidden = true;
    const close = () => { list.hidden = true; button.setAttribute('aria-expanded', 'false'); };
    const sync = () => {
      const label = select.selectedOptions[0]?.textContent || '';
      button.textContent = label + ' ▾';
      button.setAttribute('aria-label', `${select.getAttribute('aria-label') || select.labels?.[0]?.textContent || 'Selecionar'}: ${label}`);
      button.disabled = select.disabled;
      list.querySelectorAll<HTMLElement>('[role=option]').forEach((item, index) => item.setAttribute('aria-selected', String(select.selectedIndex === index)));
    };
    Array.from(select.options).forEach((option, index) => {
      const item = document.createElement('button'); item.type = 'button'; item.role = 'option'; item.textContent = option.text; item.disabled = option.disabled;
      item.onclick = () => { select.selectedIndex = index; close(); sync(); button.focus(); select.dispatchEvent(new Event('change', { bubbles: true })); };
      list.append(item);
    });
    const open = () => { list.hidden = false; button.setAttribute('aria-expanded', 'true'); (list.children[Math.max(0, select.selectedIndex)] as HTMLElement)?.focus(); };
    button.onclick = () => list.hidden ? open() : close();
    button.onkeydown = event => { if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); open(); } };
    list.onkeydown = event => {
      const options = Array.from(list.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'));
      const index = options.indexOf(document.activeElement as HTMLButtonElement);
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); button.focus(); }
      if (['ArrowDown','ArrowUp','Home','End'].includes(event.key)) {
        event.preventDefault(); const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length; options[next]?.focus();
      }
    };
    const outside = (event: Event) => { if (!wrapper.contains(event.target as Node)) close(); };
    const blur = () => { queueMicrotask(() => { if (!wrapper.contains(document.activeElement)) close(); }); };
    document.addEventListener('pointerdown', outside); wrapper.addEventListener('focusout', blur); select.addEventListener('change', sync); sync();
    cleanups.push(() => { document.removeEventListener('pointerdown', outside); wrapper.removeEventListener('focusout', blur); select.removeEventListener('change', sync); });
  });
  return () => cleanups.forEach(dispose => dispose());
}
