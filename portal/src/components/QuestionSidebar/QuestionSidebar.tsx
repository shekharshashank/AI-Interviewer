import { useState, useMemo } from "react";
import type { Question, QuestionTag } from "../../types";
import { getQuestionsByCategory } from "../../data/questions";
import { hasSavedDiagram } from "../../utils/storage";
import "./QuestionSidebar.css";

interface Props {
  questions: Question[];
  customQuestions: Question[];
  selectedQuestionId: string;
  onSelectQuestion: (id: string) => void;
  onAddCustomQuestion: (question: Question) => void;
  onDeleteCustomQuestion: (id: string) => void;
}

const TAG_OPTIONS: QuestionTag[] = [
  "SCALE",
  "CONSISTENCY",
  "REAL-TIME",
  "DATA",
  "RELIABILITY",
  "PLATFORM",
  "SECURITY",
  "ML",
];

export function QuestionSidebar({
  questions: _questions,
  customQuestions,
  selectedQuestionId,
  onSelectQuestion,
  onAddCustomQuestion,
  onDeleteCustomQuestion,
}: Props) {
  const [searchTerm, setSearchTerm] = useState("");
  const [collapsedCategories, setCollapsedCategories] = useState<Set<string>>(
    new Set()
  );
  const [showAddForm, setShowAddForm] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [newDescription, setNewDescription] = useState("");
  const [newTags, setNewTags] = useState<Set<QuestionTag>>(new Set());

  const grouped = useMemo(() => getQuestionsByCategory(), []);

  const filteredGrouped = useMemo(() => {
    if (!searchTerm.trim()) return grouped;
    const lower = searchTerm.toLowerCase();
    const result = new Map<string, Question[]>();
    for (const [cat, qs] of grouped) {
      const filtered = qs.filter(
        (q) =>
          q.title.toLowerCase().includes(lower) ||
          q.id.toLowerCase().includes(lower) ||
          q.tags.some((t) => t.toLowerCase().includes(lower))
      );
      if (filtered.length > 0) result.set(cat, filtered);
    }
    return result;
  }, [grouped, searchTerm]);

  const filteredCustom = useMemo(() => {
    if (!searchTerm.trim()) return customQuestions;
    const lower = searchTerm.toLowerCase();
    return customQuestions.filter(
      (q) =>
        q.title.toLowerCase().includes(lower) ||
        q.id.toLowerCase().includes(lower) ||
        q.tags.some((t) => t.toLowerCase().includes(lower))
    );
  }, [customQuestions, searchTerm]);

  const toggleCategory = (cat: string) => {
    setCollapsedCategories((prev) => {
      const next = new Set(prev);
      if (next.has(cat)) next.delete(cat);
      else next.add(cat);
      return next;
    });
  };

  const toggleTag = (tag: QuestionTag) => {
    setNewTags((prev) => {
      const next = new Set(prev);
      if (next.has(tag)) next.delete(tag);
      else next.add(tag);
      return next;
    });
  };

  const handleAddQuestion = () => {
    const title = newTitle.trim();
    const description = newDescription.trim();
    if (!title) return;

    const id = `C${String(customQuestions.length + 1).padStart(2, "0")}`;
    const question: Question = {
      id,
      title,
      category: "Custom",
      categoryName: "Custom Questions",
      tags: Array.from(newTags),
      description: description || `Custom question: ${title}`,
      isCustom: true,
    };

    onAddCustomQuestion(question);
    setNewTitle("");
    setNewDescription("");
    setNewTags(new Set());
    setShowAddForm(false);
  };

  return (
    <aside className="question-sidebar">
      <h2 className="sidebar-title">System Design</h2>
      <input
        type="text"
        className="sidebar-search"
        placeholder="Search questions..."
        value={searchTerm}
        onChange={(e) => setSearchTerm(e.target.value)}
      />

      <button
        className="add-question-btn"
        onClick={() => setShowAddForm((v) => !v)}
      >
        {showAddForm ? "Cancel" : "+ Add Question"}
      </button>

      {showAddForm && (
        <div className="add-question-form">
          <input
            type="text"
            className="form-input"
            placeholder="Question title *"
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleAddQuestion()}
            autoFocus
          />
          <textarea
            className="form-textarea"
            placeholder="Description (optional)"
            value={newDescription}
            onChange={(e) => setNewDescription(e.target.value)}
            rows={3}
          />
          <div className="form-tags">
            {TAG_OPTIONS.map((tag) => (
              <button
                key={tag}
                className={`form-tag ${newTags.has(tag) ? "active" : ""}`}
                onClick={() => toggleTag(tag)}
              >
                {tag}
              </button>
            ))}
          </div>
          <button
            className="form-submit"
            onClick={handleAddQuestion}
            disabled={!newTitle.trim()}
          >
            Add
          </button>
        </div>
      )}

      <nav className="question-list">
        {/* Custom questions section */}
        {filteredCustom.length > 0 && (
          <div className="category-group">
            <button
              className="category-header custom-category"
              onClick={() => toggleCategory("Custom")}
            >
              <span
                className={`chevron ${collapsedCategories.has("Custom") ? "collapsed" : ""}`}
              >
                &#9660;
              </span>
              <span className="category-name">Custom Questions</span>
              <span className="category-count">{filteredCustom.length}</span>
            </button>
            {!collapsedCategories.has("Custom") && (
              <ul className="question-items">
                {filteredCustom.map((q) => (
                  <li key={q.id}>
                    <button
                      className={`question-item ${q.id === selectedQuestionId ? "selected" : ""}`}
                      onClick={() => onSelectQuestion(q.id)}
                    >
                      <span className="question-id custom-id">{q.id}</span>
                      <span className="question-title">{q.title}</span>
                      {hasSavedDiagram(q.id) && (
                        <span
                          className="saved-indicator"
                          title="Has saved diagram"
                        />
                      )}
                      <span
                        className="delete-btn"
                        title="Delete question"
                        onClick={(e) => {
                          e.stopPropagation();
                          onDeleteCustomQuestion(q.id);
                        }}
                      >
                        &times;
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {/* Built-in questions */}
        {Array.from(filteredGrouped).map(([category, qs]) => (
          <div key={category} className="category-group">
            <button
              className="category-header"
              onClick={() => toggleCategory(category)}
            >
              <span
                className={`chevron ${collapsedCategories.has(category) ? "collapsed" : ""}`}
              >
                &#9660;
              </span>
              <span className="category-name">{category}</span>
              <span className="category-count">{qs.length}</span>
            </button>
            {!collapsedCategories.has(category) && (
              <ul className="question-items">
                {qs.map((q) => (
                  <li key={q.id}>
                    <button
                      className={`question-item ${q.id === selectedQuestionId ? "selected" : ""}`}
                      onClick={() => onSelectQuestion(q.id)}
                    >
                      <span className="question-id">{q.id}</span>
                      <span className="question-title">{q.title}</span>
                      {hasSavedDiagram(q.id) && (
                        <span
                          className="saved-indicator"
                          title="Has saved diagram"
                        />
                      )}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))}
      </nav>
    </aside>
  );
}
