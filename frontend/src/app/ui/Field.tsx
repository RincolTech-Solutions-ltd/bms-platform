import { useId, useState, type FocusEvent, type InputHTMLAttributes, type ReactNode } from 'react';

// The one form field (#520, docs/ui-rules.md rule 10): the label above, a hint under it, and the error in place of the hint.
// The error shows only once the person has left the field (or the form was sent, `showError`), never while they type, and
// stays until it is fixed. The field is described by the hint and the error and marked invalid while the error shows. Pass
// the input's own props (inputMode, autoComplete, value, onChange) as to an <input>, or draw another control with `control`,
// which is handed the id and the aria props to put on it. A `required` field says "(needed)" after its label, as the lending forms
// do, and is aria-required; the browser's own required bubble is not used. The look is .field in src/app/ui/kit.css.

export interface FieldControlProps {
  id: string;
  'aria-describedby'?: string;
  'aria-invalid': boolean;
  'aria-required'?: boolean;
  onBlur: (e: FocusEvent<HTMLElement>) => void;
}

export type FieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> & {
  label: ReactNode;
  hint?: ReactNode;
  /** What is wrong with the value now, or nothing; shown once the field has been left. */
  error?: string | null;
  /** Shows the error at once, as after a press of Save. */
  showError?: boolean;
  id?: string;
  control?: (props: FieldControlProps) => ReactNode;
};

export function Field({ label, hint, error, showError = false, id, control, onBlur, className, required = false, ...input }: FieldProps) {
  const own = useId();
  const fieldId = id ?? own;
  const [left, setLeft] = useState(false);
  const shown = error && (left || showError) ? error : null;
  const describedBy = shown ? `${fieldId}-error` : hint ? `${fieldId}-hint` : undefined;
  const props: FieldControlProps = {
    id: fieldId,
    'aria-describedby': describedBy,
    'aria-invalid': shown !== null,
    'aria-required': required || undefined,
    onBlur: (e) => {
      setLeft(true);
      onBlur?.(e as FocusEvent<HTMLInputElement>);
    },
  };
  return (
    <div className={className ? `field ${className}` : 'field'}>
      <label htmlFor={fieldId}>
        {label}
        {required && <span className="field-needed"> (needed)</span>}
      </label>
      {control ? control(props) : <input {...input} {...props} />}
      {shown ? (
        <span className="field-error" id={`${fieldId}-error`}>
          {shown}
        </span>
      ) : (
        hint && (
          <span className="field-hint" id={`${fieldId}-hint`}>
            {hint}
          </span>
        )
      )}
    </div>
  );
}
