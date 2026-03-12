import { useState } from "react";
import type { Question } from "../../types";
import "./QuestionDetail.css";

interface Props {
  question: Question;
}

export function QuestionDetail({ question }: Props) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="question-detail">
      <div className="detail-header">
        <span className="detail-id">{question.id}</span>
        <h2 className="detail-title">{question.title}</h2>
        <div className="detail-tags">
          {question.tags.map((tag) => (
            <span key={tag} className={`tag tag-${tag.toLowerCase()}`}>
              {tag}
            </span>
          ))}
        </div>
        <button
          className="detail-expand"
          onClick={() => setExpanded((e) => !e)}
          title={expanded ? "Collapse description" : "Expand description"}
        >
          {expanded ? "Hide Brief" : "Show Brief"}
        </button>
      </div>
      {expanded && (
        <div className="detail-description">{question.description}</div>
      )}
    </div>
  );
}
